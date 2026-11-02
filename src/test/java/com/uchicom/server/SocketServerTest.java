// (C) 2026 uchicom
package com.uchicom.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uchicom.util.Parameter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

  void assertEchoAndStop(BiFunction<Parameter, ServerProcessFactory, AbstractSocketServer> creator)
      throws Exception {
    TestServerProcessFactory factory = new TestServerProcessFactory();
    int port = start(creator, factory);
    assertEquals('a', echo(port, 'a'));
    assertEquals('b', echo(port, 'b'));
    // alive=falseにして、accept待ちを起こす接続を1回行い、ループが正常終了することを確認する。
    // サーバがすでにループ条件を評価済みで終了している場合は接続に失敗するので無視する。
    server.alive = false;
    try (Socket ignored = new Socket(InetAddress.getLoopbackAddress(), port)) {
      // 接続のみ
    } catch (IOException e) {
      // 無視
    }
    thread.join(5000);
    assertFalse(thread.isAlive());
    assertTrue(factory.executed.get() >= 2);
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
  void idleProcessIsForceClosed() throws Exception {
    TestServerProcessFactory factory = new TestServerProcessFactory(true, 0);
    int port = start(MultiSocketServer::new, factory);
    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
      assertTrue(factory.forceClosed.await(10, java.util.concurrent.TimeUnit.SECONDS));
    }
  }

  @Test
  void stopBeforeStart() {
    server = new SingleSocketServer(parameter(), new TestServerProcessFactory());
    server.stop();
    assertFalse(server.alive);
    server = null;
  }
}
