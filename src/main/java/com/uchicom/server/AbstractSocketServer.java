// (C) 2016 uchicom
package com.uchicom.server;

import com.uchicom.util.Parameter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * ソケットを利用した抽象サーバ.
 *
 * <p>アイドルタイムアウト: 最終アクセスからparameterの"timeout"(ミリ秒、未指定時は10000)を超えた接続は、 監視スレッドが{@link
 * ServerProcess#forceClose()}で強制クローズする。 {@link SingleSocketServer}、{@link MultiSocketServer}、{@link
 * PoolSocketServer}、{@link VirtualSocketServer}が対象。
 *
 * @author uchicom: Shigeki Uchiyama
 */
public abstract class AbstractSocketServer implements Server {
  protected Parameter parameter;
  protected ServerProcessFactory factory;

  protected List<ServerProcess> processList = new CopyOnWriteArrayList<ServerProcess>();
  protected volatile ServerSocket serverSocket;
  protected volatile Thread monitor;
  volatile boolean alive = true;

  /** アイドルタイムアウトの既定値(ミリ秒). */
  static final int DEFAULT_TIMEOUT = 10 * 1000;

  public AbstractSocketServer(Parameter parameter, ServerProcessFactory factory) {
    this.parameter = parameter;
    this.factory = factory;
  }

  @Override
  public void execute() {
    // 接続強制クローズ処理
    final long timeout = parameter.getInt("timeout", DEFAULT_TIMEOUT);
    Thread thread =
        new Thread() {
          public void run() {
            while (alive) {
              for (ServerProcess process : processList) {
                if (System.currentTimeMillis() - process.getLastTime() > timeout) {
                  // forceClose()が例外を投げても、繰り返し呼ばれないよう先に削除して監視を継続する
                  processList.remove(process);
                  try {
                    process.forceClose();
                  } catch (RuntimeException e) {
                    e.printStackTrace();
                  }
                }
              }
              try {
                Thread.sleep(1000);
              } catch (InterruptedException e) {
                // stopによる終了要求
                return;
              }
            }
          }
        };
    thread.setDaemon(true);
    monitor = thread;
    thread.start();
    try (ServerSocket serverSocket = new ServerSocketFactory(parameter).createServerSocket(); ) {
      serverSocket.setReuseAddress(true);
      serverSocket.bind(new InetSocketAddress(parameter.getInt("port")), parameter.getInt("back"));
      this.serverSocket = serverSocket;
      execute(serverSocket);
    } catch (Exception e) {
      // stopによるソケットクローズで発生する例外は正常終了として扱う
      if (alive) {
        e.printStackTrace();
      }
    } finally {
      // 異常終了した場合も含めて、監視スレッドなどの後始末を行う
      stop();
    }
  }

  /**
   * 実行開始のフックを呼んでから、アイドルタイムアウトの監視対象として登録して、処理を実行する. 処理終了時に監視対象から外す.
   *
   * <p>実行待ちの間は監視対象外のため、待機時間で開始直後にタイムアウトされないよう、登録前に{@link ServerProcess#onStart()}を呼ぶ。
   */
  protected void executeProcess(ServerProcess process) {
    try {
      process.onStart();
    } catch (RuntimeException e) {
      e.printStackTrace();
    }
    processList.add(process);
    // 登録前にstopのforceCloseRemaining()が終わっていた場合、監視されないため自分で閉じる
    if (!alive) {
      processList.remove(process);
      forceCloseQuietly(process);
      return;
    }
    try {
      process.execute();
    } finally {
      processList.remove(process);
    }
  }

  /**
   * 接続を受け付けて、ServerProcessを生成する.
   *
   * <p>acceptの一時的なIOException(fd枯渇など)や、ServerProcess生成の失敗ではnullを返す(呼び出し側はループを継続する)。
   * 生成に失敗した場合は、acceptしたソケットをcloseする。 stopによるサーバソケットのクローズ時は、IOExceptionをそのままスローする。
   *
   * @return 生成したServerProcess。継続可能な失敗時はnull
   * @throws IOException サーバソケットが閉じられている場合
   */
  protected ServerProcess acceptProcess(ServerSocket serverSocket) throws IOException {
    Socket socket;
    try {
      socket = serverSocket.accept();
    } catch (IOException e) {
      if (!alive || serverSocket.isClosed()) {
        throw e;
      }
      e.printStackTrace();
      // fd枯渇などでの高負荷なリトライを避ける
      try {
        Thread.sleep(100);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw e;
      }
      return null;
    }
    try {
      return factory.createServerProcess(parameter, socket);
    } catch (RuntimeException e) {
      e.printStackTrace();
      try {
        socket.close();
      } catch (IOException ce) {
        ce.printStackTrace();
      }
      return null;
    }
  }

  protected abstract void execute(ServerSocket serverSocket) throws IOException;

  @Override
  public void stop() {
    alive = false;
    Thread monitor = this.monitor;
    if (monitor != null) {
      monitor.interrupt();
    }
    if (serverSocket != null) {
      try {
        serverSocket.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    forceCloseRemaining();
  }

  /** ExecutorServiceへ渡す処理. 未着手のまま破棄された場合に、対象のServerProcessを特定できるよう保持する. */
  protected class ProcessTask implements Runnable {
    final ServerProcess process;

    ProcessTask(ServerProcess process) {
      this.process = process;
    }

    @Override
    public void run() {
      executeProcess(process);
    }
  }

  /** ExecutorServiceへタスクを渡す. shutdown済みで拒否された場合は、対象のServerProcessを強制クローズする. */
  protected void submitProcess(ExecutorService exec, ServerProcess process) {
    try {
      exec.execute(new ProcessTask(process));
    } catch (RejectedExecutionException e) {
      forceCloseQuietly(process);
    }
  }

  private void forceCloseQuietly(ServerProcess process) {
    try {
      process.forceClose();
    } catch (RuntimeException e) {
      e.printStackTrace();
    }
  }

  /** shutdownNow()で未着手のまま破棄されたタスクのServerProcessを強制クローズする. */
  protected void forceCloseDiscarded(List<Runnable> discarded) {
    for (Runnable task : discarded) {
      if (task instanceof ProcessTask processTask) {
        try {
          processTask.process.forceClose();
        } catch (RuntimeException e) {
          e.printStackTrace();
        }
      }
    }
  }

  /** stop後に残った接続を強制クローズする. 監視スレッドは停止しているため、タイムアウトを待たずに閉じる. */
  protected void forceCloseRemaining() {
    for (ServerProcess process : processList) {
      processList.remove(process);
      try {
        process.forceClose();
      } catch (RuntimeException e) {
        e.printStackTrace();
      }
    }
  }
}
