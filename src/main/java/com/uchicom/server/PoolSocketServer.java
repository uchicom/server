// (C) 2016 uchicom
package com.uchicom.server;

import com.uchicom.util.Parameter;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PoolSocketServer extends AbstractSocketServer {

  protected ExecutorService exec = null;

  public PoolSocketServer(Parameter parameter, ServerProcessFactory factory) {
    super(parameter, factory);
    // 残った接続でJVMの終了を妨げないよう、デーモンスレッドにする
    exec =
        Executors.newFixedThreadPool(
            parameter.getInt("pool"),
            r -> {
              Thread t = new Thread(r);
              t.setDaemon(true);
              return t;
            });
  }

  @Override
  public void stop() {
    super.stop();
    // 未着手のまま破棄されたタスクは processList に未登録のため、ここで閉じる
    forceCloseDiscarded(exec.shutdownNow());
  }

  /** メイン処理 */
  @Override
  protected void execute(ServerSocket serverSocket) throws IOException {
    while (alive) {
      final ServerProcess process = acceptProcess(serverSocket);
      if (process == null) {
        continue;
      }
      submitProcess(exec, process);
    }
  }
}
