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

  /**
   * グレースフル停止. 新規の接続受付を止めて、処理中の接続が終わるのを最大{@code timeoutMillis}ミリ秒待ち、 期限後に残った接続を強制クローズして停止する.
   *
   * <p>既存の実装を壊さないよう、既定では待機せず{@link #stop()}と同じ動作をする。
   *
   * @param timeoutMillis 待機の上限(ミリ秒)
   */
  public default void stop(long timeoutMillis) {
    stop();
  }

  /**
   * 稼働状況を文字列で返す. 外部のツール(plateのstatusコマンドなど)から、クラスローダーをまたいでも扱えるよう型を持たない文字列とする.
   *
   * <p>既存の実装を壊さないよう、既定は{@link Object#toString()}を返す。
   *
   * @return 稼働状況(複数行になることがある)
   */
  public default String status() {
    return toString();
  }
}
