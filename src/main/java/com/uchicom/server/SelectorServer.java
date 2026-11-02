// (C) 2016 uchicom
package com.uchicom.server;

import com.uchicom.util.Parameter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * nio2を使用したサーバクラス
 *
 * <p>アイドルタイムアウトの対象外。接続ごとの{@link ServerProcess}がなく、{@link Handler}が非同期に処理を進めるため。
 *
 * @author uchicom: Shigeki Uchiyama
 */
public class SelectorServer implements Server, Handler {

  protected Parameter parameter;

  protected List<ServerProcess> processList = new CopyOnWriteArrayList<ServerProcess>();

  protected HandlerFactory factory;

  protected volatile ServerSocketChannel serverChannel;
  protected volatile Selector selector;

  volatile boolean alive = true;

  public SelectorServer(Parameter parameter, HandlerFactory factory) {
    this.parameter = parameter;
    this.factory = factory;
  }

  /* (非 Javadoc)
   * @see com.uchicom.server.Server#execute()
   */
  @Override
  public void execute() {

    try (ServerSocketChannel server = ServerSocketChannel.open();
        Selector selector = Selector.open()) {
      server.socket().setReuseAddress(true);
      server
          .socket()
          .bind(new InetSocketAddress(parameter.getInt("port")), parameter.getInt("back"));
      server.configureBlocking(false);
      server.register(selector, SelectionKey.OP_ACCEPT, this);
      serverChannel = server;
      this.selector = selector;
      try {
        execute(selector);
      } finally {
        closeChannels(selector);
      }
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      alive = false;
    }
  }

  /** セレクタに登録されているチャンネル(サーバ・接続済み)をすべて閉じます. */
  protected void closeChannels(Selector selector) {
    for (SelectionKey key : selector.keys()) {
      try {
        key.channel().close();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  /** メイン処理 */
  protected void execute(Selector selector) throws IOException {
    while (alive) {
      if (selector.select() > 0) {
        Set<SelectionKey> keys = selector.selectedKeys();
        Iterator<SelectionKey> ite = keys.iterator();
        while (ite.hasNext()) {
          SelectionKey key = ite.next();
          ite.remove();
          try {
            if (key.isValid()) {
              ((Handler) key.attachment()).handle(key);
            } else {
              key.cancel();
            }
          } catch (IOException e) {
            e.printStackTrace();
            key.cancel();
          }
        }
      }
    }
  }

  @Override
  public void handle(SelectionKey key) throws IOException {
    if (key.isAcceptable()) {
      // サーバーの受付処理。
      SocketChannel socketChannel = ((ServerSocketChannel) key.channel()).accept();
      socketChannel.configureBlocking(false);
      socketChannel.register(key.selector(), SelectionKey.OP_READ, factory.createHandler());
    }
  }

  @Override
  public void stop() {
    alive = false;
    Selector selector = this.selector;
    if (selector != null) {
      // select()でブロック中のループを起こして終了させる
      selector.wakeup();
    }
  }
}
