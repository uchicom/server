// (C) 2016 uchicom
package com.uchicom.server;

import com.uchicom.util.Parameter;
import java.net.Socket;

public interface ServerProcessFactory {

  /**
   * サーバ処理クラスを作成します.
   *
   * @return
   */
  public ServerProcess createServerProcess(Parameter parameter, Socket socket);
}
