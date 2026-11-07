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
    AtomicInteger count = new AtomicInteger();
    TestServerProcessFactory echo = new TestServerProcessFactory();
    CapturingLogger captured = new CapturingLogger();
    int port =
        start(
            creator,
            (parameter, socket) -> {
              if (count.getAndIncrement() == 0) {
                throw new IllegalStateException("boom");
              }
              return echo.createServerProcess(parameter, socket);
            },
            captured);
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
    assertTrue(
        captured.records.stream()
            .anyMatch(r -> r.thrown() != null && "boom".equals(r.thrown().getMessage())));
  }

  /** ログを記録するLogger. */
  static class CapturingLogger implements System.Logger {
    record Record(System.Logger.Level level, String message, Throwable thrown) {}

    final java.util.List<Record> records = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public String getName() {
      return "capturing";
    }

    @Override
    public boolean isLoggable(Level level) {
      return true;
    }

    @Override
    public void log(Level level, java.util.ResourceBundle bundle, String msg, Throwable thrown) {
      records.add(new Record(level, msg, thrown));
    }

    @Override
    public void log(Level level, java.util.ResourceBundle bundle, String format, Object... params) {
      records.add(
          new Record(
              level,
              params == null ? format : java.text.MessageFormat.format(format, params),
              null));
    }
  }

  int start(
      BiFunction<Parameter, ServerProcessFactory, AbstractSocketServer> creator,
      ServerProcessFactory factory,
      System.Logger logger)
      throws Exception {
    return start(creator, factory, logger, parameter());
  }

  int start(
      BiFunction<Parameter, ServerProcessFactory, AbstractSocketServer> creator,
      ServerProcessFactory factory,
      System.Logger logger,
      Parameter parameter)
      throws Exception {
    server = creator.apply(parameter, factory);
    server.logger = logger;
    thread = new Thread(server::execute);
    thread.setDaemon(true);
    thread.start();
    for (int i = 0; i < 500 && server.serverSocket == null; i++) {
      Thread.sleep(10);
    }
    return server.serverSocket.getLocalPort();
  }

  /** accept失敗が連続しても、初回のみ詳細・以降は一定件数ごとに件数のみ出力され、復旧時に通知されることを確認する. */
  @Test
  void acceptFailuresAreThrottled() throws Exception {
    server = new SingleSocketServer(parameter(), new TestServerProcessFactory());
    CapturingLogger captured = new CapturingLogger();
    server.logger = captured;
    server.acceptRetryWaitMillis = 1;
    int failures = AbstractSocketServer.ACCEPT_FAILURE_LOG_INTERVAL * 2 + 1;
    AtomicInteger count = new AtomicInteger();
    java.net.ServerSocket failing =
        new java.net.ServerSocket() {
          @Override
          public Socket accept() throws IOException {
            if (count.getAndIncrement() < failures) {
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
    // 初回(スタックトレース付き) + 100件目 + 200件目 + 復旧通知
    assertEquals(4, captured.records.size());
    assertEquals("accept failed", captured.records.get(0).message());
    assertTrue(captured.records.get(0).thrown() != null);
    assertTrue(captured.records.get(1).message().contains("100"));
    assertTrue(captured.records.get(2).message().contains("200"));
    assertEquals(System.Logger.Level.INFO, captured.records.get(3).level());
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
    int port =
        start(
            (p, f) -> {
              // タイムアウトは実行開始時刻が基準のため、短くして待ち時間を抑える
              p.put("timeout", "300");
              return new MultiSocketServer(p, f);
            },
            factory);
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

  /** 接続順にforceCloseを記録し、実行は共有のlatchが解放されるまでブロックするFactory. */
  static class BlockingFactory implements ServerProcessFactory {
    final CountDownLatch release = new CountDownLatch(1);
    final CountDownLatch[] closed = {
      new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1)
    };
    final AtomicInteger created = new AtomicInteger();
    final AtomicInteger executed = new AtomicInteger();

    @Override
    public ServerProcess createServerProcess(Parameter parameter, java.net.Socket socket) {
      final int index = created.getAndIncrement();
      return new ServerProcess() {
        @Override
        public long getLastTime() {
          return System.currentTimeMillis();
        }

        @Override
        public void forceClose() {
          closed[index].countDown();
          try {
            socket.close();
          } catch (IOException e) {
            // 無視
          }
        }

        @Override
        public void execute() {
          executed.incrementAndGet();
          try {
            release.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
      };
    }
  }

  PoolSocketServer startBlockingPool(BlockingFactory factory, String key, String value)
      throws Exception {
    int port =
        start(
            (p, f) -> {
              p.put("pool", "1");
              p.put("timeout", "60000");
              p.put(key, value);
              return new PoolSocketServer(p, f);
            },
            factory);
    // 1件目を実行中にしておく
    Socket first = new Socket(InetAddress.getLoopbackAddress(), port);
    sockets.add(first);
    for (int i = 0; i < 500 && factory.executed.get() < 1; i++) {
      Thread.sleep(10);
    }
    assertEquals(1, factory.executed.get());
    this.port = port;
    return (PoolSocketServer) server;
  }

  int port;
  final java.util.List<Socket> sockets = new java.util.ArrayList<>();

  @AfterEach
  void closeSockets() throws Exception {
    for (Socket socket : sockets) {
      socket.close();
    }
  }

  /** 実行待ちの上限を超えた接続だけが、受け付け直後に閉じられることを確認する. */
  @Test
  void poolQueueLimit() throws Exception {
    BlockingFactory factory = new BlockingFactory();
    startBlockingPool(factory, "queue", "1");
    // 2件目は実行待ちに入る
    sockets.add(new Socket(InetAddress.getLoopbackAddress(), port));
    for (int i = 0; i < 500 && factory.created.get() < 2; i++) {
      Thread.sleep(10);
    }
    // 3件目は上限超過で閉じられる
    sockets.add(new Socket(InetAddress.getLoopbackAddress(), port));
    assertTrue(factory.closed[2].await(10, TimeUnit.SECONDS));
    assertEquals(1, factory.closed[0].getCount());
    assertEquals(1, factory.closed[1].getCount());
    assertEquals(1, factory.executed.get());
    // 解放すると、実行待ちだった2件目が処理される
    factory.release.countDown();
    for (int i = 0; i < 500 && factory.executed.get() < 2; i++) {
      Thread.sleep(10);
    }
    assertEquals(2, factory.executed.get());
  }

  /** 実行待ちが上限なしの場合(既定)は、閉じられないことを確認する. */
  @Test
  void poolQueueUnlimitedByDefault() throws Exception {
    BlockingFactory factory = new BlockingFactory();
    startBlockingPool(factory, "queueTimeout", "60000");
    sockets.add(new Socket(InetAddress.getLoopbackAddress(), port));
    sockets.add(new Socket(InetAddress.getLoopbackAddress(), port));
    for (int i = 0; i < 500 && factory.created.get() < 3; i++) {
      Thread.sleep(10);
    }
    assertEquals(3, factory.created.get());
    assertFalse(factory.closed[1].await(500, TimeUnit.MILLISECONDS));
    assertFalse(factory.closed[2].await(10, TimeUnit.MILLISECONDS));
    factory.release.countDown();
  }

  /** 実行待ちのタイムアウトを超えた接続だけが閉じられることを確認する. */
  @Test
  void poolQueueTimeout() throws Exception {
    BlockingFactory factory = new BlockingFactory();
    startBlockingPool(factory, "queueTimeout", "300");
    sockets.add(new Socket(InetAddress.getLoopbackAddress(), port));
    assertTrue(factory.closed[1].await(10, TimeUnit.SECONDS));
    // 実行中の1件目は、アイドルタイムアウト内のため閉じられない
    assertEquals(1, factory.closed[0].getCount());
    assertEquals(1, factory.executed.get());
    factory.release.countDown();
  }

  /** 最大同時接続数を超えた接続だけが閉じられ、既存の接続は影響を受けず、解放後は再び受け付けることを確認する. */
  void assertMaxConnection(
      BiFunction<Parameter, ServerProcessFactory, AbstractSocketServer> creator) throws Exception {
    BlockingFactory factory = new BlockingFactory();
    int port =
        start(
            (p, f) -> {
              p.put("timeout", "60000");
              p.put("maxConnection", "1");
              return creator.apply(p, f);
            },
            factory);
    sockets.add(new Socket(InetAddress.getLoopbackAddress(), port));
    for (int i = 0; i < 500 && factory.executed.get() < 1; i++) {
      Thread.sleep(10);
    }
    assertEquals(1, factory.executed.get());
    // 2件目は上限超過で閉じられる
    sockets.add(new Socket(InetAddress.getLoopbackAddress(), port));
    assertTrue(factory.closed[1].await(10, TimeUnit.SECONDS));
    assertEquals(1, factory.closed[0].getCount());
    assertEquals(1, factory.executed.get());
    // 1件目が終了すると枠が解放され、3件目は処理される
    factory.release.countDown();
    for (int i = 0; i < 500 && factory.executed.get() < 1; i++) {
      Thread.sleep(10);
    }
    Thread.sleep(300);
    sockets.add(new Socket(InetAddress.getLoopbackAddress(), port));
    for (int i = 0; i < 500 && factory.executed.get() < 2; i++) {
      Thread.sleep(10);
    }
    assertEquals(2, factory.executed.get());
    assertEquals(1, factory.closed[2].getCount());
  }

  @Test
  void multiMaxConnection() throws Exception {
    assertMaxConnection(MultiSocketServer::new);
  }

  @Test
  void virtualMaxConnection() throws Exception {
    assertMaxConnection(VirtualSocketServer::new);
  }

  /** maxConnection未指定時は、複数の同時接続がすべて処理されることを確認する. */
  void assertMaxConnectionUnlimitedByDefault(
      BiFunction<Parameter, ServerProcessFactory, AbstractSocketServer> creator) throws Exception {
    BlockingFactory factory = new BlockingFactory();
    int port = start(creator, factory);
    for (int i = 0; i < 3; i++) {
      sockets.add(new Socket(InetAddress.getLoopbackAddress(), port));
    }
    for (int i = 0; i < 500 && factory.executed.get() < 3; i++) {
      Thread.sleep(10);
    }
    assertEquals(3, factory.executed.get());
    factory.release.countDown();
  }

  @Test
  void multiMaxConnectionUnlimitedByDefault() throws Exception {
    assertMaxConnectionUnlimitedByDefault(MultiSocketServer::new);
  }

  @Test
  void virtualMaxConnectionUnlimitedByDefault() throws Exception {
    assertMaxConnectionUnlimitedByDefault(VirtualSocketServer::new);
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

  private ServerProcess countingProcess(AtomicInteger closed, AtomicInteger executed) {
    return new ServerProcess() {
      @Override
      public long getLastTime() {
        return System.currentTimeMillis();
      }

      @Override
      public void forceClose() {
        closed.incrementAndGet();
      }

      @Override
      public void execute() {
        executed.incrementAndGet();
      }
    };
  }

  /** shutdown済みのExecutorServiceに拒否された接続が、強制クローズされることを確認する. */
  @Test
  void submitRejectedForceCloses() throws Exception {
    for (String type : new String[] {"pool", "virtual"}) {
      Parameter parameter = parameter();
      parameter.put("pool", "1");
      AbstractSocketServer s =
          type.equals("pool")
              ? new PoolSocketServer(parameter, new TestServerProcessFactory())
              : new VirtualSocketServer(parameter, new TestServerProcessFactory());
      java.util.concurrent.ExecutorService exec =
          type.equals("pool") ? ((PoolSocketServer) s).exec : ((VirtualSocketServer) s).exec;
      exec.shutdownNow();
      AtomicInteger closed = new AtomicInteger();
      AtomicInteger executed = new AtomicInteger();
      s.submitProcess(exec, countingProcess(closed, executed));
      assertEquals(1, closed.get(), type);
      assertEquals(0, executed.get(), type);
    }
  }

  /** stop済みのサーバでexecuteProcessが始まった場合、実行せずに強制クローズされることを確認する. */
  @Test
  void executeProcessAfterStopForceCloses() throws Exception {
    AbstractSocketServer s = new MultiSocketServer(parameter(), new TestServerProcessFactory());
    s.alive = false;
    AtomicInteger closed = new AtomicInteger();
    AtomicInteger executed = new AtomicInteger();
    s.executeProcess(countingProcess(closed, executed));
    assertEquals(1, closed.get());
    assertEquals(0, executed.get());
    assertTrue(s.processList.isEmpty());
  }

  /** forceClose()が例外を投げても、監視が継続して他の接続がタイムアウトで閉じられ、再呼び出しされないことを確認する. */
  @Test
  void forceCloseExceptionContinuesMonitoring() throws Exception {
    CapturingLogger captured = new CapturingLogger();
    {
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
      int port = start(MultiSocketServer::new, factory, captured, parameter);
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
    }
    assertTrue(
        captured.records.stream()
            .anyMatch(r -> r.thrown() != null && "closeboom".equals(r.thrown().getMessage())));
  }

  /** 待機した接続が実行開始の直後にforceClose()されないことを確認する. */
  @Test
  void queuedProcessIsNotTimedOutImmediately() throws Exception {
    AtomicInteger count = new AtomicInteger();
    CountDownLatch busyStarted = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch queuedFinished = new CountDownLatch(1);
    java.util.concurrent.atomic.AtomicLong execStart = new java.util.concurrent.atomic.AtomicLong();
    java.util.concurrent.atomic.AtomicLong closedAt =
        new java.util.concurrent.atomic.AtomicLong(-1);
    long timeout = 300;
    ServerProcessFactory factory =
        (parameter, socket) ->
            new ServerProcess() {
              final int index = count.getAndIncrement();
              final long created = System.currentTimeMillis();

              @Override
              public long getLastTime() {
                // 先頭2接続は常に通信中扱い。待機した接続は生成時刻のまま更新しない
                return index < 2 ? System.currentTimeMillis() : created;
              }

              @Override
              public void forceClose() {
                if (index >= 2) {
                  closedAt.compareAndSet(-1, System.currentTimeMillis());
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
                  if (index < 2) {
                    busyStarted.countDown();
                    release.await();
                    return;
                  }
                  execStart.set(System.currentTimeMillis());
                  // 実行開始後、監視スレッドが判定する時間を与える
                  Thread.sleep(1500);
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
        Thread.sleep(timeout * 3);
        release.countDown();
        assertTrue(queuedFinished.await(10, TimeUnit.SECONDS));
        // 実行時間(1500ms)はtimeoutを超えるので最終的にはタイムアウトするが、
        // 待機時間を含めた判定で開始直後に閉じられることはなく、実行開始からtimeout経過後に閉じられる
        assertTrue(closedAt.get() >= 0);
        assertTrue(closedAt.get() - execStart.get() >= timeout);
      }
    }
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
