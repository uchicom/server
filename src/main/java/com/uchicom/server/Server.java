// (C) 2016 uchicom
package com.uchicom.server;

/**
 * サーバインターフェース.
 *
 * @author uchicom: Shigeki Uchiyama
 */
public interface Server {

  /**
   * サーバの処理. 呼び出したスレッドをブロックする.
   *
   * <p>{@link #stop()} による停止では、例外を投げずに正常にreturnする。 起動の失敗(bind失敗・SSL設定の誤り・キーストアの読み込み失敗など)や、
   * 実行中の想定外の異常終了では、原因を cause に持つ {@link IllegalStateException} を投げる。
   *
   * @throws IllegalStateException 起動失敗または異常終了した場合
   */
  public void execute();

  /** サーバ停止. */
  public void stop();
}
