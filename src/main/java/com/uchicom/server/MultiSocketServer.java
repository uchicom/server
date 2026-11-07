// (C) 2016 uchicom
package com.uchicom.server;

import com.uchicom.util.Parameter;
import java.io.IOException;
import java.lang.System.Logger.Level;
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
      try {
        startThread(thread);
      } catch (Throwable e) {
        // native thread不足(OutOfMemoryError)などでも、その接続だけ諦めてサーバを継続する
        if (e instanceof VirtualMachineError && !(e instanceof OutOfMemoryError)) {
          throw (VirtualMachineError) e;
        }
        releaseConnection();
        logger.log(Level.ERROR, "thread start failed, connection dropped", e);
        forceCloseQuietly(process);
        waitAfterStartFailure();
      }
    }
  }

  /** スレッドを開始する. テストで失敗を再現できるよう、パッケージプライベートとしている. */
  void startThread(Thread thread) {
    thread.start();
  }

  /** 一時的な資源枯渇からの回復を待つ. */
  private void waitAfterStartFailure() {
    try {
      Thread.sleep(acceptRetryWaitMillis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
