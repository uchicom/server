// (C) 2016 uchicom
package com.uchicom.server;

import com.uchicom.util.Parameter;
import java.io.IOException;
import java.net.ServerSocket;

public class MultiSocketServer extends AbstractSocketServer {

  volatile boolean alive = true;

  public MultiSocketServer(Parameter parameter, ServerProcessFactory factory) {
    super(parameter, factory);
  }

  /** メイン処理 */
  @Override
  protected void execute(ServerSocket serverSocket) throws IOException {
    while (alive) {
      final ServerProcess process = factory.createServerProcess(parameter, serverSocket.accept());
      processList.add(process);
      Thread thread =
          new Thread() {
            public void run() {
              process.execute();
              processList.remove(process);
            }
          };
      thread.setDaemon(true);
      thread.start();
    }
  }

  @Override
  public void stop() {
    alive = false;
  }
}
