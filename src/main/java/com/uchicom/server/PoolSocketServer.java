// (C) 2016 uchicom
package com.uchicom.server;

import com.uchicom.util.Parameter;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class PoolSocketServer extends AbstractSocketServer {

  protected ThreadPoolExecutor exec = null;

  /** 実行待ちのタイムアウト(ミリ秒). */
  private final long queueTimeout;

  public PoolSocketServer(Parameter parameter, ServerProcessFactory factory) {
    super(parameter, factory);
    int pool = parameter.getInt("pool");
    // 実行待ちの上限。未指定時は無制限。上限を超えた接続は拒否され、強制クローズされる
    int queue = parameter.getInt("queue", Integer.MAX_VALUE);
    queueTimeout = parameter.getInt("queueTimeout", parameter.getInt("timeout", DEFAULT_TIMEOUT));
    // 残った接続でJVMの終了を妨げないよう、デーモンスレッドにする
    exec =
        new ThreadPoolExecutor(
            pool,
            pool,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(queue),
            r -> {
              Thread t = new Thread(r);
              t.setDaemon(true);
              return t;
            });
  }

  @Override
  protected List<String> requiredKeys() {
    return List.of("port", "back", "pool");
  }

  /** 実行待ちの時間が{@code queueTimeout}を超えた接続を、キューから取り除いて強制クローズする. */
  @Override
  protected void checkQueued(long now) {
    for (Runnable task : exec.getQueue()) {
      ProcessTask processTask = (ProcessTask) task;
      // 取り除けた場合のみ未着手。実行開始済みなら何もしない
      if (now - processTask.queuedTime > queueTimeout && exec.getQueue().remove(task)) {
        taskRemoved();
        logQueueTimeout(queueTimeout);
        forceCloseQuietly(processTask.process);
      }
    }
  }

  @Override
  protected int queuedCount() {
    return exec.getQueue().size();
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
