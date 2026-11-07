// (C) 2016 uchicom
package com.uchicom.server;

import com.uchicom.util.Parameter;
import java.io.IOException;
import java.net.ServerSocket;

public class MultiSocketServer extends AbstractSocketServer {

  public MultiSocketServer(Parameter parameter, ServerProcessFactory factory) {
    super(parameter, factory);
  }

  /** メイン処理 */
  @Override
  protected void execute(ServerSocket serverSocket) throws IOException {
    while (alive) {
      final ServerProcess process = acceptProcess(serverSocket);
      if (process == null) {
        continue;
      }
      // 上限に達している場合は、acquireConnectionで接続をクローズする
      if (!acquireConnection(process)) {
        continue;
      }
      Thread thread =
          new Thread() {
            public void run() {
              try {
                executeProcess(process);
              } finally {
                releaseConnection();
              }
            }
          };
      thread.setDaemon(true);
      thread.start();
    }
  }
}
