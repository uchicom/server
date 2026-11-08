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

  /**
   * 最終アクセス時刻を返します.
   *
   * <p>サーバの監視スレッドが定期的に呼び出し、現在時刻との差がタイムアウトを超えた接続を {@link #forceClose()} で強制クローズします。 監視スレッドと {@link
   * #execute()} を実行するスレッドは別のため、実装はスレッドセーフにする(例: {@code volatile})こと。
   *
   * @return 最終アクセス時刻(エポックミリ秒)
   */
  public long getLastTime();

  /**
   * 接続を強制的にクローズします.
   *
   * <p>タイムアウト検知時、サーバ停止時、実行待ちのまま破棄された場合などに、{@link #execute()} を実行中のスレッドとは別のスレッドから呼ばれます。 実行中の {@link
   * #execute()} のブロッキングI/Oを中断できるよう、ソケットのクローズなどを行ってください。 複数回呼ばれても問題がないようにし(冪等)、 {@link #execute()}
   * が呼ばれる前に呼ばれる場合もあります。 例外はサーバ側でログ出力されますが、処理は継続されます。
   */
  public void forceClose();

  /**
   * 接続ごとの処理を実行します.
   *
   * <p>接続の受付ごとに1回、サーバが管理するスレッド(サーバ種別により接続ごと、またはスレッドプール)から呼ばれます。 処理が終わるまでの間、 {@link #getLastTime()}
   * による監視の対象になります。
   */
  public void execute();
}
