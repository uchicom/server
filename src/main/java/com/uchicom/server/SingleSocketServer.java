package com.uchicom.server;

import java.io.IOException;
import java.net.ServerSocket;

import com.uchicom.util.Parameter;

public class SingleSocketServer extends AbstractSocketServer {

	public SingleSocketServer(Parameter parameter, ServerProcessFactory factory) {
		super(parameter, factory);
	}

	/** メイン処理
	 *
	 */
	@Override
	protected void execute(ServerSocket serverSocket) throws IOException {
        while (true) {
            ServerProcess process = factory.createServerProcess(parameter, serverSocket.accept());
            process.execute();
        }
	}

}
