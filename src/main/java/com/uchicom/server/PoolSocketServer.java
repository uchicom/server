package com.uchicom.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PoolSocketServer extends AbstractSocketServer {

	protected ExecutorService exec = null;
	public PoolSocketServer(Parameter parameter, ServerProcessFactory factory) {
		super(parameter, factory);
		exec = Executors.newFixedThreadPool(parameter.getInt("pool"));
	}

	/**
	 * メイン処理
	 *
	 */
	@Override
	protected void execute(ServerSocket serverSocket) throws IOException {
		while (true) {
			final ServerProcess process = factory.createServerProcess(parameter, serverSocket.accept());
			exec.execute(new Runnable() {
				@Override
				public void run() {
					process.execute();
				}
			});
		}
	}

}
