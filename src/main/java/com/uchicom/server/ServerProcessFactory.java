// (C) 2016 uchicom
package com.uchicom.server;

import com.uchicom.util.Parameter;
import java.net.Socket;

/** 接続ごとの {@link ServerProcess} を生成するファクトリ. */
public interface ServerProcessFactory {

  /**
   * サーバ処理クラスを作成します.
   *
   * <p>接続を受け付けるたびに、accept を行うスレッドから呼ばれます。 {@link RuntimeException}
   * を投げた場合、サーバはログ出力して該当ソケットをクローズし、次の接続の受付を継続します。
   *
   * @param parameter サーバ起動時のパラメータ
   * @param socket 受け付けた接続のソケット
   * @return 接続を処理する {@link ServerProcess}
   */
  public ServerProcess createServerProcess(Parameter parameter, Socket socket);
}
