// (C) 2026 uchicom
package com.uchicom.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.uchicom.util.Parameter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SelectorServerTest {

  SelectorServer server;
  Thread thread;

  @AfterEach
  void tearDown() {
    SelectorServer.shutdown(null);
    SelectorServer.serverQueue.clear();
  }

  int start(HandlerFactory factory) throws Exception {
    SelectorServer.serverQueue.clear();
    Parameter parameter = new Parameter(new String[0]);
    parameter.put("port", "0");
    parameter.put("back", "50");
    server = new SelectorServer(parameter, factory);
    thread = new Thread(server::execute);
    thread.setDaemon(true);
    thread.start();
    for (int i = 0; i < 500 && SelectorServer.serverQueue.isEmpty(); i++) {
      Thread.sleep(10);
    }
    return SelectorServer.serverQueue.peek().socket().getLocalPort();
  }

  /** stop後に接続してselectを起こし、ループを終了させる. */
  void stopServer(int port) throws Exception {
    server.stop();
    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
      thread.join(5000);
    }
    assertFalse(thread.isAlive());
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
  void shutdownClosesServerChannel() throws Exception {
    start(() -> echoHandler());
    assertEquals(1, SelectorServer.serverQueue.size());
    SelectorServer.shutdown(null);
    assertEquals(0, SelectorServer.serverQueue.size());
    // キューが空でも例外にならない
    SelectorServer.shutdown(null);
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
