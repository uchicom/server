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

  @Test
  void unexpectedExceptionStopsServer() throws Exception {
    PrintStream originalErr = System.err;
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    System.setErr(new PrintStream(err, true));
    try {
      int port =
          start(
              SingleSocketServer::new,
              (parameter, socket) -> {
                throw new IllegalStateException("boom");
              });
      try (Socket ignored = new Socket(InetAddress.getLoopbackAddress(), port)) {
        // 接続のみ
      }
      thread.join(5000);
      assertFalse(thread.isAlive());
      server.monitor.join(5000);
      assertFalse(server.monitor.isAlive());
    } finally {
      System.setErr(originalErr);
    }
    // 想定外の例外はstopによるものと区別して出力される
    assertTrue(err.toString().contains("boom"));
  }

  @Test
  void idleProcessIsForceClosed() throws Exception {
    TestServerProcessFactory factory = new TestServerProcessFactory(true, 0);
    int port = start(MultiSocketServer::new, factory);
    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
      assertTrue(factory.forceClosed.await(10, java.util.concurrent.TimeUnit.SECONDS));
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
