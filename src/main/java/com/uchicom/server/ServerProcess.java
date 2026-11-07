// (C) 2016 uchicom
package com.uchicom.server;

/**
 * 接続ごとの処理.
 *
 * <p>注意: 実装クラスの {@link Object#equals(Object)} / {@link Object#hashCode()} は、同一性ベース({@link Object}
 * の既定の実装)のままにすること。サーバは監視対象の管理({@code List#remove} や {@code Map} のキー)で {@code equals} / {@code
 * hashCode} に依存しているため、値ベースでオーバーライドすると、別の接続が同一視されて、誤って監視対象から外れたり、 登録時刻が上書きされたりする。
 */
public interface ServerProcess {

  public long getLastTime();

  public void forceClose();

  public void execute();
}
