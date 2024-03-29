// (C) 2016 uchicom
package com.uchicom.server;

public interface HandlerFactory {

  /**
   * ハンドラークラスを作成します.
   *
   * @return
   */
  public Handler createHandler();
}
