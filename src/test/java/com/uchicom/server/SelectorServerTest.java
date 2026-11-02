// (C) 2026 uchicom
package com.uchicom.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uchicom.util.Parameter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SelectorServerTest {

  SelectorServer server;
  Thread thread;
  final List<SelectorServer> servers = new ArrayList<>();
  final List<Thread> threads = new ArrayList<>();

  @AfterEach
  void tearDown() throws Exception {
    for (SelectorServer started : servers) {
      started.stop();
    }
    for (Thread started : threads) {
      started.join(5000);
    }
  }

  SelectorServer startServer(HandlerFactory factory) throws Exception {
    Parameter parameter = new Parameter(new String[0]);
    parameter.put("port", "0");
    parameter.put("back", "50");
    SelectorServer selectorServer = new SelectorServer(parameter, factory);
    Thread selectorThread = new Thread(selectorServer::execute);
    selectorThread.setDaemon(true);
    selectorThread.start();
    servers.add(selectorServer);
    threads.add(selectorThread);
    for (int i = 0; i < 500 && selectorServer.selector == null; i++) {
      Thread.sleep(10);
    }
    server = selectorServer;
    thread = selectorThread;
    return selectorServer;
  }

  int start(HandlerFactory factory) throws Exception {
    return startServer(factory).serverChannel.socket().getLocalPort();
  }

  /** stopだけでループが終了し、チャンネルとセレクタが閉じられることを確認する. */
  void stopServer(int port) throws Exception {
    ServerSocketChannel channel = server.serverChannel;
    Selector selector = server.selector;
    server.stop();
    thread.join(5000);
    assertFalse(thread.isAlive());
    assertFalse(channel.isOpen());
    assertFalse(selector.isOpen());
  }

  static Handler echoHandler() {
    return key -> {
      SocketChannel channel = (SocketChannel) key.channel();
      ByteBuffer buffer = ByteBuffer.allocate(16);
      int length = channel.read(buffer);
      if (length < 0) {
        channel.close();
        return;
      }
      buffer.flip();
      channel.write(buffer);
    };
  }

  @Test
  void echo() throws Exception {
    int port = start(() -> echoHandler());
    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
      socket.setSoTimeout(5000);
      socket.getOutputStream().write('x');
      socket.getOutputStream().flush();
      assertEquals('x', socket.getInputStream().read());
      socket.getOutputStream().write('y');
      socket.getOutputStream().flush();
      assertEquals('y', socket.getInputStream().read());
    }
    stopServer(port);
  }

  @Test
  void handlerIOExceptionCancelsKey() throws Exception {
    int port =
        start(
            () ->
                key -> {
                  throw new IOException("test");
                });
    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
      socket.setSoTimeout(5000);
      socket.getOutputStream().write('x');
      socket.getOutputStream().flush();
      // キャンセルされたキーの後も次の接続を受け付ける
    }
    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
      socket.getOutputStream().write('y');
      socket.getOutputStream().flush();
    }
    stopServer(port);
  }

  @Test
  void invalidKeyIsCancelled() throws Exception {
    int port = start(() -> echoHandler());
    // 接続後すぐ閉じることでクライアント切断を処理させる
    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
      socket.getOutputStream().write('x');
    }
    stopServer(port);
  }

  @Test
  void stopClosesConnectedChannels() throws Exception {
    int port = start(() -> echoHandler());
    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
      socket.setSoTimeout(5000);
      socket.getOutputStream().write('x');
      socket.getOutputStream().flush();
      assertEquals('x', socket.getInputStream().read());
      stopServer(port);
      // サーバ側の接続済みチャンネルも閉じられるので、EOFが返る
      assertEquals(-1, socket.getInputStream().read());
    }
  }

  @Test
  void stopBeforeExecute() throws Exception {
    SelectorServer selectorServer = newServer();
    selectorServer.stop();
    assertFalse(selectorServer.alive);
    // stopを先に呼んでいてもexecuteはすぐ戻る
    selectorServer.parameter.put("port", "0");
    selectorServer.parameter.put("back", "50");
    Thread selectorThread = new Thread(selectorServer::execute);
    selectorThread.setDaemon(true);
    selectorThread.start();
    selectorThread.join(5000);
    assertFalse(selectorThread.isAlive());
    assertFalse(selectorServer.serverChannel.isOpen());
  }

  @Test
  void stopTwiceIsHarmless() throws Exception {
    start(() -> echoHandler());
    server.stop();
    thread.join(5000);
    assertFalse(thread.isAlive());
    // 終了後のstopでも例外にならない
    server.stop();
  }

  @Test
  void instancesStopIndependently() throws Exception {
    SelectorServer first = startServer(() -> echoHandler());
    int firstPort = first.serverChannel.socket().getLocalPort();
    Thread firstThread = thread;
    SelectorServer second = startServer(() -> echoHandler());
    int secondPort = second.serverChannel.socket().getLocalPort();
    Thread secondThread = thread;

    first.stop();
    firstThread.join(5000);
    assertFalse(firstThread.isAlive());
    assertFalse(first.serverChannel.isOpen());

    // 一方を止めても、もう一方は動き続ける
    assertTrue(secondThread.isAlive());
    assertTrue(second.serverChannel.isOpen());
    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), secondPort)) {
      socket.setSoTimeout(5000);
      socket.getOutputStream().write('z');
      socket.getOutputStream().flush();
      assertEquals('z', socket.getInputStream().read());
    }
    assertThrows(
        IOException.class, () -> new Socket(InetAddress.getLoopbackAddress(), firstPort).close());

    server = second;
    thread = secondThread;
    stopServer(secondPort);
  }

  SelectorServer newServer() {
    return new SelectorServer(new Parameter(new String[0]), () -> echoHandler());
  }

  @Test
  void executeReturnsWhenWokenUpWithoutKeys() throws Exception {
    SelectorServer selectorServer = newServer();
    try (Selector selector = Selector.open()) {
      Thread loop =
          new Thread(
              () -> {
                try {
                  selectorServer.execute(selector);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
      loop.setDaemon(true);
      loop.start();
      Thread.sleep(200);
      selectorServer.stop();
      selector.wakeup();
      loop.join(5000);
      assertFalse(loop.isAlive());
    }
  }

  @Test
  void executeCancelsInvalidKey() throws Exception {
    SelectorServer selectorServer = newServer();
    Handler cancelAll =
        key -> {
          for (SelectionKey other : key.selector().keys()) {
            other.cancel();
          }
          selectorServer.stop();
        };
    Pipe pipe1 = Pipe.open();
    Pipe pipe2 = Pipe.open();
    try (Selector selector = Selector.open()) {
      for (Pipe pipe : new Pipe[] {pipe1, pipe2}) {
        pipe.source().configureBlocking(false);
        pipe.source().register(selector, SelectionKey.OP_READ, cancelAll);
        pipe.sink().write(ByteBuffer.wrap(new byte[] {1}));
      }
      selectorServer.execute(selector);
      assertFalse(selectorServer.alive);
    } finally {
      pipe1.source().close();
      pipe1.sink().close();
      pipe2.source().close();
      pipe2.sink().close();
    }
  }

  @Test
  void handleIgnoresNonAcceptableKey() throws Exception {
    SelectorServer selectorServer = newServer();
    Pipe pipe = Pipe.open();
    try (Selector selector = Selector.open()) {
      pipe.source().configureBlocking(false);
      SelectionKey key = pipe.source().register(selector, SelectionKey.OP_READ);
      selectorServer.handle(key);
      assertEquals(1, selector.keys().size());
    } finally {
      pipe.source().close();
      pipe.sink().close();
    }
  }
}
