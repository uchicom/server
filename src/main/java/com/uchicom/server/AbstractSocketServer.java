// (C) 2016 uchicom
package com.uchicom.server;

import com.uchicom.util.Parameter;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

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
  /** ロガー名. */
  static final String LOGGER_NAME = "com.uchicom.server";

  /** accept失敗が連続した場合に、件数をログ出力する間隔(件数). */
  static final int ACCEPT_FAILURE_LOG_INTERVAL = 100;

  /** テストで差し替えられるよう、パッケージプライベートとしている. */
  Logger logger = System.getLogger(LOGGER_NAME);

  /** accept失敗後の待機時間(ミリ秒). テストで短縮できるよう、パッケージプライベートとしている. */
  volatile long acceptRetryWaitMillis = 100;

  /** accept失敗の連続回数. acceptスレッドからのみ更新する. */
  private int acceptFailures;

  protected Parameter parameter;
  protected ServerProcessFactory factory;

  protected List<ServerProcess> processList = new CopyOnWriteArrayList<ServerProcess>();

  /** 監視対象に登録した時刻(実行開始時刻). 実行待ちの時間をアイドル時間に含めないために使う. */
  private final Map<ServerProcess, Long> startTimes = new ConcurrentHashMap<>();

  protected volatile ServerSocket serverSocket;
  protected volatile Thread monitor;
  volatile boolean alive = true;

  /** アイドルタイムアウトの既定値(ミリ秒). */
  static final int DEFAULT_TIMEOUT = 10 * 1000;

  /** 最大同時接続数. 未指定時は上限なし. {@link MultiSocketServer}、{@link VirtualSocketServer}が対象. */
  private final int maxConnection;

  /** 同時接続数の制限対象として処理中の接続数. */
  private final AtomicInteger connections = new AtomicInteger();

  public AbstractSocketServer(Parameter parameter, ServerProcessFactory factory) {
    this.parameter = parameter;
    this.factory = factory;
    validateRequiredKeys();
    validateNumericKeys();
    this.maxConnection = parameter.getInt("maxConnection", Integer.MAX_VALUE);
  }

  /** 数値キーの有効範囲(最小値, 最大値). 指定されたキーのみ検証する. */
  private static final Map<String, int[]> NUMERIC_RANGES = new LinkedHashMap<>();

  static {
    NUMERIC_RANGES.put("port", new int[] {0, 65535});
    NUMERIC_RANGES.put("back", new int[] {0, Integer.MAX_VALUE});
    NUMERIC_RANGES.put("pool", new int[] {1, Integer.MAX_VALUE});
    NUMERIC_RANGES.put("queue", new int[] {1, Integer.MAX_VALUE});
    NUMERIC_RANGES.put("queueTimeout", new int[] {1, Integer.MAX_VALUE});
    NUMERIC_RANGES.put("timeout", new int[] {1, Integer.MAX_VALUE});
    NUMERIC_RANGES.put("maxConnection", new int[] {1, Integer.MAX_VALUE});
  }

  /** 数値キーの形式と範囲をまとめて検証する. 不正なキーをすべて列挙して失敗する. */
  private void validateNumericKeys() {
    List<String> errors = new ArrayList<>();
    for (Map.Entry<String, int[]> entry : NUMERIC_RANGES.entrySet()) {
      String key = entry.getKey();
      if (!parameter.is(key)) {
        continue;
      }
      String value = parameter.get(key);
      int min = entry.getValue()[0];
      int max = entry.getValue()[1];
      try {
        int number = Integer.parseInt(value == null ? "" : value.trim());
        if (number < min || number > max) {
          errors.add(key + "=" + value + " (" + rangeText(min, max) + "で指定してください)");
        }
      } catch (NumberFormatException e) {
        errors.add(key + "=" + value + " (整数で指定してください)");
      }
    }
    if (!errors.isEmpty()) {
      throw new IllegalArgumentException("キーの値が不正です: " + String.join(", ", errors));
    }
  }

  private static String rangeText(int min, int max) {
    return max == Integer.MAX_VALUE ? min + "以上" : min + "〜" + max;
  }

  /** 必須キーの一覧. サブクラスで必須キーが増える場合はオーバーライドする. */
  protected List<String> requiredKeys() {
    return List.of("port", "back");
  }

  /** 必須キーの未指定をまとめて検証する. 不足キーをすべて列挙して失敗する. */
  private void validateRequiredKeys() {
    List<String> missing = new ArrayList<>();
    for (String key : requiredKeys()) {
      if (!parameter.is(key)) {
        missing.add(key);
      }
    }
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException("必須のキーが指定されていません: " + String.join(", ", missing));
    }
  }

  /**
   * 同時接続数の枠を確保する. 上限に達している場合は確保せず、接続を強制クローズする.
   *
   * @return 確保できた場合true。呼び出し側は処理終了時に{@link #releaseConnection()}を呼ぶ
   */
  protected boolean acquireConnection(ServerProcess process) {
    if (connections.incrementAndGet() <= maxConnection) {
      return true;
    }
    connections.decrementAndGet();
    forceCloseQuietly(process);
    return false;
  }

  protected void releaseConnection() {
    connections.decrementAndGet();
  }

  @Override
  public void execute() {
    // 接続強制クローズ処理
    final long timeout = parameter.getInt("timeout", DEFAULT_TIMEOUT);
    Thread thread =
        new Thread() {
          public void run() {
            while (alive) {
              checkQueued(System.currentTimeMillis());
              for (ServerProcess process : processList) {
                long now = System.currentTimeMillis();
                // 最終アクセス時刻と実行開始時刻のうち新しい方を基準にする
                long base = Math.max(process.getLastTime(), startTimes.getOrDefault(process, 0L));
                if (now - base > timeout) {
                  // forceClose()が例外を投げても、繰り返し呼ばれないよう先に削除して監視を継続する
                  unregister(process);
                  try {
                    process.forceClose();
                  } catch (RuntimeException e) {
                    logger.log(Level.ERROR, "forceClose failed", e);
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
      serverSocket.bind(createBindAddress(), parameter.getInt("back"));
      this.serverSocket = serverSocket;
      execute(serverSocket);
    } catch (Exception e) {
      // stopによるソケットクローズで発生する例外は正常終了として扱う
      if (alive) {
        logger.log(Level.ERROR, "server terminated abnormally", e);
        throw new IllegalStateException("server terminated abnormally", e);
      }
    } finally {
      // 異常終了した場合も含めて、監視スレッドなどの後始末を行う
      stop();
    }
  }

  /** bindするアドレスを生成する. parameterの"host"未指定時は全インターフェースで待ち受ける. */
  InetSocketAddress createBindAddress() {
    int port = parameter.getInt("port");
    String host = parameter.get("host");
    if (host == null || host.isEmpty()) {
      return new InetSocketAddress(port);
    }
    return new InetSocketAddress(host, port);
  }

  /**
   * アイドルタイムアウトの監視対象として登録して、処理を実行する. 処理終了時に監視対象から外す.
   *
   * <p>実行待ちの間は監視対象外のため、登録時刻を記録し、タイムアウト判定は{@link ServerProcess#getLastTime()}と登録時刻の新しい方を基準にする。
   * これにより、待機時間で開始直後にタイムアウトされない。
   */
  protected void executeProcess(ServerProcess process) {
    startTimes.put(process, System.currentTimeMillis());
    processList.add(process);
    // 登録前にstopのforceCloseRemaining()が終わっていた場合、監視されないため自分で閉じる
    if (!alive) {
      unregister(process);
      forceCloseQuietly(process);
      return;
    }
    try {
      process.execute();
    } finally {
      unregister(process);
    }
  }

  /** 監視スレッドから定期的に呼ばれる. 実行待ちの接続を監視する場合にオーバーライドする. 既定では何もしない. */
  protected void checkQueued(long now) {}

  private void unregister(ServerProcess process) {
    processList.remove(process);
    startTimes.remove(process);
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
      logAcceptFailure(e);
      // fd枯渇などでの高負荷なリトライを避ける
      try {
        Thread.sleep(acceptRetryWaitMillis);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw e;
      }
      return null;
    }
    resetAcceptFailures();
    try {
      return factory.createServerProcess(parameter, socket);
    } catch (RuntimeException e) {
      logger.log(Level.ERROR, "createServerProcess failed", e);
      try {
        socket.close();
      } catch (IOException ce) {
        logger.log(Level.ERROR, "socket close failed", ce);
      }
      return null;
    }
  }

  /** accept失敗をログ出力する. 連続時は初回のみスタックトレース付きで出力し、以降は一定件数ごとに件数のみ出力する. */
  private void logAcceptFailure(IOException e) {
    acceptFailures++;
    if (acceptFailures == 1) {
      logger.log(Level.ERROR, "accept failed", e);
    } else if (acceptFailures % ACCEPT_FAILURE_LOG_INTERVAL == 0) {
      logger.log(
          Level.ERROR, "accept failed {0} times in a row, last: {1}", acceptFailures, e.toString());
    }
  }

  /** accept成功時に連続失敗のカウントをリセットする. 間引いたログがある場合は復旧を通知する. */
  private void resetAcceptFailures() {
    if (acceptFailures > 1) {
      logger.log(Level.INFO, "accept recovered after {0} failures", acceptFailures);
    }
    acceptFailures = 0;
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
        logger.log(Level.ERROR, "server socket close failed", e);
      }
    }
    forceCloseRemaining();
  }

  /** ExecutorServiceへ渡す処理. 未着手のまま破棄された場合に、対象のServerProcessを特定できるよう保持する. */
  protected class ProcessTask implements Runnable {
    final ServerProcess process;

    /** 実行待ちに入った時刻. */
    final long queuedTime = System.currentTimeMillis();

    /** 同時接続数の枠を保持していて、処理終了時に解放する場合true. */
    final boolean counted;

    ProcessTask(ServerProcess process, boolean counted) {
      this.process = process;
      this.counted = counted;
    }

    @Override
    public void run() {
      try {
        executeProcess(process);
      } finally {
        if (counted) {
          releaseConnection();
        }
      }
    }
  }

  /** ExecutorServiceへタスクを渡す. shutdown済みで拒否された場合は、対象のServerProcessを強制クローズする. */
  protected void submitProcess(ExecutorService exec, ServerProcess process) {
    submitProcess(exec, process, false);
  }

  /** counted=trueの場合、確保済みの同時接続数の枠をタスク終了時(拒否された場合は即時)に解放する. */
  protected void submitProcess(ExecutorService exec, ServerProcess process, boolean counted) {
    try {
      exec.execute(new ProcessTask(process, counted));
    } catch (RejectedExecutionException e) {
      if (counted) {
        releaseConnection();
      }
      forceCloseQuietly(process);
    }
  }

  protected void forceCloseQuietly(ServerProcess process) {
    try {
      process.forceClose();
    } catch (RuntimeException e) {
      logger.log(Level.ERROR, "forceClose failed", e);
    }
  }

  /** shutdownNow()で未着手のまま破棄されたタスクのServerProcessを強制クローズする. */
  protected void forceCloseDiscarded(List<Runnable> discarded) {
    for (Runnable task : discarded) {
      if (task instanceof ProcessTask processTask) {
        try {
          processTask.process.forceClose();
        } catch (RuntimeException e) {
          logger.log(Level.ERROR, "forceClose failed", e);
        }
      }
    }
  }

  /** stop後に残った接続を強制クローズする. 監視スレッドは停止しているため、タイムアウトを待たずに閉じる. */
  protected void forceCloseRemaining() {
    for (ServerProcess process : processList) {
      unregister(process);
      try {
        process.forceClose();
      } catch (RuntimeException e) {
        logger.log(Level.ERROR, "forceClose failed", e);
      }
    }
  }
}
