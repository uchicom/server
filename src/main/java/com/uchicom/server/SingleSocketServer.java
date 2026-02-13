// (C) 2016 uchicom
package com.uchicom.server;

import com.uchicom.util.Parameter;
import java.io.IOException;
import java.net.ServerSocket;

public class SingleSocketServer extends AbstractSocketServer {

  public SingleSocketServer(Parameter parameter, ServerProcessFactory factory) {
    super(parameter, factory);
  }

  /** メイン処理 */
  @Override
  protected void execute(ServerSocket serverSocket) throws IOException {
    while (alive) {
      ServerProcess process = factory.createServerProcess(parameter, serverSocket.accept());
      process.execute();
    }
  }
}
