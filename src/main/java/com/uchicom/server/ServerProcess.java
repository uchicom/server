// (C) 2016 uchicom
package com.uchicom.server;

public interface ServerProcess {

  /**
   * 実行開始時に、監視対象へ登録する直前に呼ばれるフック.
   *
   * <p>PoolSocketServerなどでプール満杯により実行待ちとなった接続は、待機中はアイドルタイムアウトの対象外となる。 待機時間で実行開始直後にタイムアウトされないよう、
   * 実装側で{@link #getLastTime()}が返す時刻をここで更新する。 既定の実装は何もしない。
   */
  public default void onStart() {}

  public long getLastTime();

  public void forceClose();

  public void execute();
}
