package com.uchicom.server;

import java.io.IOException;
import java.net.ServerSocket;

public class MultiSocketServer extends AbstractSocketServer {

	public MultiSocketServer(Parameter parameter, ServerProcessFactory factory) {
		super(parameter, factory);
	}
	/**
	 * メイン処理
	 *
	 */
	@Override
	protected void execute(ServerSocket serverSocket) throws IOException {
		while (true) {
			final ServerProcess process = factory.createServerProcess(parameter, serverSocket.accept());
			processList.add(process);
			Thread thread = new Thread() {
				public void run() {
					process.execute();
				}
			};
			thread.setDaemon(true);
			thread.start();
		}
	}

}
