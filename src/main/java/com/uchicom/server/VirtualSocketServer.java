// (C) 2016 uchicom
package com.uchicom.server;

import com.uchicom.util.Parameter;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VirtualSocketServer extends AbstractSocketServer {

  protected ExecutorService exec = null;

  public VirtualSocketServer(Parameter parameter, ServerProcessFactory factory) {
    super(parameter, factory);
    exec = Executors.newVirtualThreadPerTaskExecutor();
  }

  /** メイン処理 */
  @Override
  protected void execute(ServerSocket serverSocket) throws IOException {
    while (alive) {
      final ServerProcess process = factory.createServerProcess(parameter, serverSocket.accept());
      exec.execute(process::execute);
    }
  }
}
