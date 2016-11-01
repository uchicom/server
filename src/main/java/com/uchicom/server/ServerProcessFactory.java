package com.uchicom.server;

import java.net.Socket;

public interface ServerProcessFactory {

	/**
	 * サーバ処理クラスを作成します.
	 * @return
	 */
	public ServerProcess createServerProcess(Parameter parameter, Socket socket);
}
