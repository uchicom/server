// (C) 2026 uchicom
package com.uchicom.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uchicom.util.Parameter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SocketServerTest {

  AbstractSocketServer server;
  Thread thread;

  @AfterEach
  void tearDown() throws Exception {
    if (server != null) {
      server.stop();
      thread.join(5000);
    }
  }

  Parameter parameter() {
    Parameter parameter = new Parameter(new String[0]);
    parameter.put("port", "0");
    parameter.put("back", "50");
    parameter.put("pool", "2");
    return parameter;
  }

  int start(
      BiFunction<Parameter, ServerProcessFactory, AbstractSocketServer> creator,
      ServerProcessFactory factory)
      throws Exception {
    server = creator.apply(parameter(), factory);
    thread = new Thread(server::execute);
    thread.setDaemon(true);
    thread.start();
    for (int i = 0; i < 500 && server.serverSocket == null; i++) {
      Thread.sleep(10);
    }
    return server.serverSocket.getLocalPort();
  }

  int echo(int port, int value) throws Exception {
    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
      socket.setSoTimeout(5000);
      OutputStream out = socket.getOutputStream();
      out.write(value);
      out.flush();
      InputStream in = socket.getInputStream();
      return in.read();
    }
  }

  /** stopだけで、例外を出力せずにスレッド・監視スレッド・Executorが終了することを確認する. */
  void assertEchoAndStop(BiFunction<Parameter, ServerProcessFactory, AbstractSocketServer> creator)
      throws Exception {
    PrintStream originalErr = System.err;
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    System.setErr(new PrintStream(err, true));
    try {
      TestServerProcessFactory factory = new TestServerProcessFactory();
      int port = start(creator, factory);
      assertEquals('a', echo(port, 'a'));
      assertEquals('b', echo(port, 'b'));
      Thread monitor = server.monitor;

      server.stop();
      thread.join(5000);
      assertFalse(thread.isAlive());
      monitor.join(5000);
      assertFalse(monitor.isAlive());
      if (server instanceof PoolSocketServer pool) {
        assertTrue(pool.exec.isShutdown());
      }
      if (server instanceof VirtualSocketServer virtual) {
        assertTrue(virtual.exec.isShutdown());
      }
      assertEquals(2, factory.executed.get());
    } finally {
      System.setErr(originalErr);
    }
    assertEquals("", err.toString());
  }

  /** alive=falseでaccept待ちを起こした場合も、ループが正常終了することを確認する. */
  void assertLoopEndsWhenNotAlive(
      BiFunction<Parameter, ServerProcessFactory, AbstractSocketServer> creator) throws Exception {
    TestServerProcessFactory factory = new TestServerProcessFactory();
    int port = start(creator, factory);
    assertEquals('a', echo(port, 'a'));
    // サーバがすでにループ条件を評価済みで終了している場合は接続に失敗するので無視する。
    server.alive = false;
    try (Socket ignored = new Socket(InetAddress.getLoopbackAddress(), port)) {
      // 接続のみ
    } catch (IOException e) {
      // 無視
    }
    thread.join(5000);
    assertFalse(thread.isAlive());
    server.monitor.join(5000);
    assertFalse(server.monitor.isAlive());
    assertTrue(factory.executed.get() >= 1);
  }

  @Test
  void single() throws Exception {
    assertEchoAndStop(SingleSocketServer::new);
  }

  @Test
  void multi() throws Exception {
    assertEchoAndStop(MultiSocketServer::new);
  }

  @Test
  void pool() throws Exception {
    assertEchoAndStop(PoolSocketServer::new);
  }

  @Test
  void virtual() throws Exception {
    assertEchoAndStop(VirtualSocketServer::new);
  }

  @Test
  void singleLoopEnds() throws Exception {
    assertLoopEndsWhenNotAlive(SingleSocketServer::new);
  }

  @Test
  void multiLoopEnds() throws Exception {
    assertLoopEndsWhenNotAlive(MultiSocketServer::new);
  }

  @Test
  void poolLoopEnds() throws Exception {
    assertLoopEndsWhenNotAlive(PoolSocketServer::new);
  }

  @Test
  void virtualLoopEnds() throws Exception {
    assertLoopEndsWhenNotAlive(VirtualSocketServer::new);
  }

  /** createServerProcessが例外を投げても、サーバが継続して次の接続を処理し、acceptしたソケットがcloseされることを確認する. */
  void assertCreateFailureContinues(
      BiFunction<Parameter, ServerProcessFactory, AbstractSocketServer> creator) throws Exception {
    PrintStream originalErr = System.err;
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    System.setErr(new PrintStream(err, true));
    try {
      AtomicInteger count = new AtomicInteger();
      TestServerProcessFactory echo = new TestServerProcessFactory();
      int port =
          start(
              creator,
              (parameter, socket) -> {
                if (count.getAndIncrement() == 0) {
                  throw new IllegalStateException("boom");
                }
                return echo.createServerProcess(parameter, socket);
              });
      try (Socket failed = new Socket(InetAddress.getLoopbackAddress(), port)) {
        failed.setSoTimeout(5000);
        // サーバ側でcloseされるので、EOF(または切断)になる
        try {
          assertEquals(-1, failed.getInputStream().read());
        } catch (IOException e) {
          // RSTによる切断も許容する
        }
      }
      assertEquals('a', echo(port, 'a'));
      assertTrue(thread.isAlive());
    } finally {
      System.setErr(originalErr);
    }
    assertTrue(err.toString().contains("boom"));
  }

  @Test
  void singleCreateFailure() throws Exception {
    assertCreateFailureContinues(SingleSocketServer::new);
  }

  @Test
  void multiCreateFailure() throws Exception {
    assertCreateFailureContinues(MultiSocketServer::new);
  }

  @Test
  void poolCreateFailure() throws Exception {
    assertCreateFailureContinues(PoolSocketServer::new);
  }

  @Test
  void virtualCreateFailure() throws Exception {
    assertCreateFailureContinues(VirtualSocketServer::new);
  }

  @Test
  void acceptIOExceptionContinues() throws Exception {
    PrintStream originalErr = System.err;
    System.setErr(new PrintStream(new ByteArrayOutputStream(), true));
    try {
      server = new SingleSocketServer(parameter(), new TestServerProcessFactory());
      AtomicInteger count = new AtomicInteger();
      java.net.ServerSocket failing =
          new java.net.ServerSocket() {
            @Override
            public Socket accept() throws IOException {
              if (count.getAndIncrement() == 0) {
                throw new IOException("too many open files");
              }
              return super.accept();
            }
          };
      failing.bind(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      server.serverSocket = failing;
      thread =
          new Thread(
              () -> {
                try {
                  server.execute(failing);
                } catch (IOException e) {
                  // 無視
                }
              });
      thread.setDaemon(true);
      thread.start();
      assertEquals('a', echo(failing.getLocalPort(), 'a'));
      assertTrue(count.get() >= 2);
      assertTrue(thread.isAlive());
    } finally {
      System.setErr(originalErr);
      server.stop();
    }
  }

  @Test
  void idleProcessIsForceClosed() throws Exception {
    TestServerProcessFactory factory = new TestServerProcessFactory(true, 0);
    int port = start(MultiSocketServer::new, factory);
    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
      assertTrue(factory.forceClosed.await(10, java.util.concurrent.TimeUnit.SECONDS));
    }
  }

  /** 無通信の接続がタイムアウトで強制クローズされ、次の接続を処理できることを確認する. */
  void assertIdleForceClosedThenNext(
      BiFunction<Parameter, ServerProcessFactory, AbstractSocketServer> creator) throws Exception {
    AtomicInteger count = new AtomicInteger();
    CountDownLatch idleClosed = new CountDownLatch(1);
    TestServerProcessFactory echo = new TestServerProcessFactory();
    ServerProcessFactory factory =
        (parameter, socket) -> {
          if (count.getAndIncrement() == 0) {
            // 最初の接続は何も送られず、強制クローズされるまで読み取りで待つ
            return new ServerProcess() {
              final long start = System.currentTimeMillis();

              @Override
              public long getLastTime() {
                return start;
              }

              @Override
              public void forceClose() {
                try {
                  socket.close();
                } catch (IOException e) {
                  // 無視
                }
              }

              @Override
              public void execute() {
                try (socket) {
                  socket.getInputStream().read();
                } catch (IOException e) {
                  // 強制クローズによる例外
                } finally {
                  idleClosed.countDown();
                }
              }
            };
          }
          return echo.createServerProcess(parameter, socket);
        };
    Parameter parameter = parameter();
    parameter.put("timeout", "300");
    server = creator.apply(parameter, factory);
    thread = new Thread(server::execute);
    thread.setDaemon(true);
    thread.start();
    for (int i = 0; i < 500 && server.serverSocket == null; i++) {
      Thread.sleep(10);
    }
    int port = server.serverSocket.getLocalPort();
    try (Socket idle = new Socket(InetAddress.getLoopbackAddress(), port)) {
      assertTrue(idleClosed.await(10, TimeUnit.SECONDS));
      assertEquals('a', echo(port, 'a'));
    }
  }

  @Test
  void singleIdleTimeout() throws Exception {
    assertIdleForceClosedThenNext(SingleSocketServer::new);
  }

  @Test
  void multiIdleTimeout() throws Exception {
    assertIdleForceClosedThenNext(MultiSocketServer::new);
  }

  @Test
  void poolIdleTimeout() throws Exception {
    assertIdleForceClosedThenNext(PoolSocketServer::new);
  }

  @Test
  void virtualIdleTimeout() throws Exception {
    assertIdleForceClosedThenNext(VirtualSocketServer::new);
  }

  /** stop()後に残った接続が、タイムアウトを待たずに強制クローズされ、スレッドが終了することを確認する. */
  void assertStopForceClosesRemaining(
      BiFunction<Parameter, ServerProcessFactory, AbstractSocketServer> creator) throws Exception {
    CountDownLatch forceClosed = new CountDownLatch(1);
    CountDownLatch executed = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(1);
    ServerProcessFactory factory =
        (parameter, socket) ->
            new ServerProcess() {
              @Override
              public long getLastTime() {
                return System.currentTimeMillis();
              }

              @Override
              public void forceClose() {
                forceClosed.countDown();
                try {
                  socket.close();
                } catch (IOException e) {
                  // 無視
                }
              }

              @Override
              public void execute() {
                executed.countDown();
                try (socket) {
                  socket.getInputStream().read();
                } catch (IOException e) {
                  // 強制クローズによる例外
                } finally {
                  finished.countDown();
                }
              }
            };
    Parameter parameter = parameter();
    // タイムアウトでは閉じられない十分長い値
    parameter.put("timeout", "600000");
    server = creator.apply(parameter, factory);
    thread = new Thread(server::execute);
    thread.setDaemon(true);
    thread.start();
    for (int i = 0; i < 500 && server.serverSocket == null; i++) {
      Thread.sleep(10);
    }
    int port = server.serverSocket.getLocalPort();
    try (Socket idle = new Socket(InetAddress.getLoopbackAddress(), port)) {
      assertTrue(executed.await(10, TimeUnit.SECONDS));
      server.stop();
      assertTrue(forceClosed.await(10, TimeUnit.SECONDS));
      assertTrue(finished.await(10, TimeUnit.SECONDS));
    }
    thread.join(5000);
    assertFalse(thread.isAlive());
  }

  @Test
  void singleStopForceCloses() throws Exception {
    assertStopForceClosesRemaining(SingleSocketServer::new);
  }

  @Test
  void multiStopForceCloses() throws Exception {
    assertStopForceClosesRemaining(MultiSocketServer::new);
  }

  @Test
  void poolStopForceCloses() throws Exception {
    assertStopForceClosesRemaining(PoolSocketServer::new);
  }

  @Test
  void virtualStopForceCloses() throws Exception {
    assertStopForceClosesRemaining(VirtualSocketServer::new);
  }

  /** poolを超えて未着手のまま待たされている接続が、stop()でshutdownNow()に破棄されても強制クローズされることを確認する. */
  @Test
  void poolStopForceClosesQueued() throws Exception {
    int pool = 2;
    int total = pool + 1;
    CountDownLatch executing = new CountDownLatch(pool);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch allClosed = new CountDownLatch(total);
    java.util.Set<ServerProcess> closed = java.util.concurrent.ConcurrentHashMap.newKeySet();
    java.util.Set<ServerProcess> started = java.util.concurrent.ConcurrentHashMap.newKeySet();
    ServerProcessFactory factory =
        (parameter, socket) ->
            new ServerProcess() {
              @Override
              public long getLastTime() {
                return System.currentTimeMillis();
              }

              @Override
              public void forceClose() {
                closed.add(this);
                release.countDown();
                allClosed.countDown();
              }

              @Override
              public void execute() {
                started.add(this);
                executing.countDown();
                try {
                  release.await();
                } catch (InterruptedException e) {
                  // shutdownNowによる割り込み
                }
              }
            };
    Parameter parameter = parameter();
    parameter.put("pool", String.valueOf(pool));
    parameter.put("timeout", "600000");
    server = new PoolSocketServer(parameter, factory);
    thread = new Thread(server::execute);
    thread.setDaemon(true);
    thread.start();
    for (int i = 0; i < 500 && server.serverSocket == null; i++) {
      Thread.sleep(10);
    }
    int port = server.serverSocket.getLocalPort();
    java.util.List<Socket> sockets = new java.util.ArrayList<>();
    try {
      for (int i = 0; i < total; i++) {
        sockets.add(new Socket(InetAddress.getLoopbackAddress(), port));
      }
      assertTrue(executing.await(10, TimeUnit.SECONDS));
      // 3つ目がacceptされてキューに入るのを待つ
      Thread.sleep(500);
      assertEquals(pool, started.size());
      server.stop();
      assertTrue(allClosed.await(10, TimeUnit.SECONDS));
      assertEquals(total, closed.size());
    } finally {
      for (Socket s : sockets) {
        s.close();
      }
    }
  }

  /** forceClose()が例外を投げても、監視が継続して他の接続がタイムアウトで閉じられ、再呼び出しされないことを確認する. */
  @Test
  void forceCloseExceptionContinuesMonitoring() throws Exception {
    PrintStream originalErr = System.err;
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    System.setErr(new PrintStream(err, true));
    try {
      AtomicInteger count = new AtomicInteger();
      AtomicInteger throwingCalls = new AtomicInteger();
      CountDownLatch secondClosed = new CountDownLatch(1);
      ServerProcessFactory factory =
          (parameter, socket) ->
              new ServerProcess() {
                final int index = count.getAndIncrement();
                final long start = System.currentTimeMillis();

                @Override
                public long getLastTime() {
                  return start;
                }

                @Override
                public void forceClose() {
                  if (index == 0) {
                    throwingCalls.incrementAndGet();
                    throw new IllegalStateException("closeboom");
                  }
                  try {
                    socket.close();
                  } catch (IOException e) {
                    // 無視
                  }
                }

                @Override
                public void execute() {
                  try (socket) {
                    socket.getInputStream().read();
                  } catch (IOException e) {
                    // 強制クローズによる例外
                  } finally {
                    if (index == 1) {
                      secondClosed.countDown();
                    }
                  }
                }
              };
      Parameter parameter = parameter();
      parameter.put("timeout", "300");
      server = new MultiSocketServer(parameter, factory);
      thread = new Thread(server::execute);
      thread.setDaemon(true);
      thread.start();
      for (int i = 0; i < 500 && server.serverSocket == null; i++) {
        Thread.sleep(10);
      }
      int port = server.serverSocket.getLocalPort();
      try (Socket first = new Socket(InetAddress.getLoopbackAddress(), port)) {
        // 最初の接続のforceClose()が例外を投げるまで待つ
        for (int i = 0; i < 500 && throwingCalls.get() == 0; i++) {
          Thread.sleep(20);
        }
        assertEquals(1, throwingCalls.get());
        try (Socket second = new Socket(InetAddress.getLoopbackAddress(), port)) {
          assertTrue(secondClosed.await(10, TimeUnit.SECONDS));
        }
        // 例外を投げたプロセスは監視対象から外れ、再度forceClose()されない
        Thread.sleep(1500);
        assertEquals(1, throwingCalls.get());
        assertTrue(server.monitor.isAlive());
      }
    } finally {
      System.setErr(originalErr);
    }
    assertTrue(err.toString().contains("closeboom"));
  }

  /** プール満杯で待機した接続は、実行開始時のonStart()で時刻が更新され、待機時間が経過扱いにならないことを確認する. */
  @Test
  void queuedProcessRefreshesLastTimeOnStart() throws Exception {
    AtomicInteger count = new AtomicInteger();
    CountDownLatch busyStarted = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch queuedFinished = new CountDownLatch(1);
    java.util.concurrent.atomic.AtomicLong idleAtStart =
        new java.util.concurrent.atomic.AtomicLong(-1);
    AtomicInteger queuedStarts = new AtomicInteger();
    long timeout = 300;
    ServerProcessFactory factory =
        (parameter, socket) ->
            new ServerProcess() {
              final int index = count.getAndIncrement();
              volatile long lastTime = System.currentTimeMillis();

              @Override
              public void onStart() {
                if (index >= 2) {
                  queuedStarts.incrementAndGet();
                }
                lastTime = System.currentTimeMillis();
              }

              @Override
              public long getLastTime() {
                // 先頭2接続は常に通信中扱いにして、タイムアウトさせない
                return index < 2 ? System.currentTimeMillis() : lastTime;
              }

              @Override
              public void forceClose() {
                try {
                  socket.close();
                } catch (IOException e) {
                  // 無視
                }
              }

              @Override
              public void execute() {
                try (socket) {
                  if (index < 2) {
                    busyStarted.countDown();
                    release.await();
                    return;
                  }
                  idleAtStart.set(System.currentTimeMillis() - getLastTime());
                } catch (Exception e) {
                  // 無視
                } finally {
                  if (index >= 2) {
                    queuedFinished.countDown();
                  }
                }
              }
            };
    Parameter parameter = parameter();
    parameter.put("timeout", String.valueOf(timeout));
    server = new PoolSocketServer(parameter, factory);
    thread = new Thread(server::execute);
    thread.setDaemon(true);
    thread.start();
    for (int i = 0; i < 500 && server.serverSocket == null; i++) {
      Thread.sleep(10);
    }
    int port = server.serverSocket.getLocalPort();
    try (Socket a = new Socket(InetAddress.getLoopbackAddress(), port);
        Socket b = new Socket(InetAddress.getLoopbackAddress(), port)) {
      assertTrue(busyStarted.await(10, TimeUnit.SECONDS));
      try (Socket queued = new Socket(InetAddress.getLoopbackAddress(), port)) {
        // 待機がtimeoutを大きく超えるまで、プールを塞いだままにする
        Thread.sleep(timeout * 3);
        assertEquals(0, queuedStarts.get());
        release.countDown();
        assertTrue(queuedFinished.await(10, TimeUnit.SECONDS));
        assertEquals(1, queuedStarts.get());
        // 実行開始時に更新されているので、待機時間は含まれない
        assertTrue(idleAtStart.get() >= 0 && idleAtStart.get() < timeout * 2);
      }
    }
  }

  /** onStart()が例外を投げても、スタックトレースを出力して処理を実行することを確認する. */
  @Test
  void onStartExceptionStillExecutes() throws Exception {
    PrintStream originalErr = System.err;
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    System.setErr(new PrintStream(err, true));
    try {
      AtomicInteger executed = new AtomicInteger();
      AbstractSocketServer server =
          new SingleSocketServer(parameter(), new TestServerProcessFactory());
      server.executeProcess(
          new ServerProcess() {
            @Override
            public void onStart() {
              throw new IllegalStateException("startboom");
            }

            @Override
            public long getLastTime() {
              return System.currentTimeMillis();
            }

            @Override
            public void forceClose() {}

            @Override
            public void execute() {
              executed.incrementAndGet();
            }
          });
      assertEquals(1, executed.get());
      assertTrue(server.processList.isEmpty());
    } finally {
      System.setErr(originalErr);
    }
    assertTrue(err.toString().contains("startboom"));
  }

  @Test
  void stopBeforeExecute() throws Exception {
    server = new SingleSocketServer(parameter(), new TestServerProcessFactory());
    server.stop();
    assertFalse(server.alive);
    // stopを先に呼んでいてもexecuteはすぐ戻る
    thread = new Thread(server::execute);
    thread.setDaemon(true);
    thread.start();
    thread.join(5000);
    assertFalse(thread.isAlive());
  }
}
