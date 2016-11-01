// (c) 2016 uchicom
package com.uchicom.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ソケットを利用した抽象サーバ.
 *
 * @author uchicom: Shigeki Uchiyama
 *
 */
public abstract class AbstractSocketServer implements Server {
	protected Parameter parameter;
	protected ServerProcessFactory factory;

	protected List<ServerProcess> processList = new CopyOnWriteArrayList<ServerProcess>();
	protected static Queue<ServerSocket> serverQueue = new ConcurrentLinkedQueue<ServerSocket>();

	public AbstractSocketServer(Parameter parameter, ServerProcessFactory factory) {
		this.parameter = parameter;
		this.factory = factory;
	}

	@Override
	public void execute() {
		// 接続強制クローズ処理
		Thread thread = new Thread() {
			public void run() {
				while (true) {
					for (ServerProcess process : processList) {
						if (System.currentTimeMillis() - process.getLastTime() > 10 * 1000) {
							process.forceClose();
							processList.remove(process);
						}
					}
					try {
						Thread.sleep(1000);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}
		};
		thread.setDaemon(true);
		thread.start();
		try (ServerSocket serverSocket = new ServerSocketFactory(parameter).createServerSocket();) {
			serverSocket.setReuseAddress(true);
			serverSocket.bind(new InetSocketAddress(parameter.getInt("port")), parameter.getInt("back"));
			serverQueue.add(serverSocket);
			execute(serverSocket);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	protected abstract void execute(ServerSocket serverSocket) throws IOException;

	public static void shutdown(String[] args) {
		if (!serverQueue.isEmpty()) {
			try {
				serverQueue.poll().close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}
