// (c) 2016 uchicom
package com.uchicom.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * nio2を使用した抽象サーバクラス
 *
 * @author uchicom: Shigeki Uchiyama
 *
 */
public abstract class AbstractSelectorServer implements Server, Handler {

	protected static Queue<ServerSocketChannel> serverQueue = new ConcurrentLinkedQueue<ServerSocketChannel>();
	protected Parameter parameter;

	protected List<ServerProcess> processList = new CopyOnWriteArrayList<ServerProcess>();

	protected HandlerFactory factory;

	public AbstractSelectorServer(Parameter parameter, HandlerFactory factory) {
		this.parameter = parameter;
		this.factory = factory;
	}

	/* (非 Javadoc)
	 * @see com.uchicom.dirpop3.Server#execute()
	 */
	@Override
	public void execute() {

		try (ServerSocketChannel server = ServerSocketChannel.open();) {
			server.socket().setReuseAddress(true);
			server.socket().bind(new InetSocketAddress(parameter.getInt("port")), parameter.getInt("back"));
			server.configureBlocking(false);
			serverQueue.add(server);

			Selector selector = Selector.open();
			server.register(selector,
					SelectionKey.OP_ACCEPT,
					this);
			execute(selector);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	abstract protected void execute(Selector selector) throws IOException;

	public static void shutdown(String[] args) {
		if (!serverQueue.isEmpty()) {
			try {
				serverQueue.poll().close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	@Override
    public void handle(SelectionKey key) throws IOException {
        if (key.isAcceptable()) {
            //サーバーの受付処理。
            SocketChannel socketChannel = ((ServerSocketChannel)key.channel()).accept();
            socketChannel.configureBlocking(false);
            socketChannel.register(key.selector(), SelectionKey.OP_READ, factory.createHandler());
        }
    }
}
