server
=================


[![Maven Central](https://img.shields.io/maven-central/v/com.uchicom/server.svg)](http://search.maven.org/#search|ga|1|com.uchicom.server)
[![Maven Central](https://img.shields.io/github/license/uchicom/server.svg)](http://www.apache.org/licenses/LICENSE-2.0.txt)
いろいろなサーバプログラムの基本となる抽象サーバ

導入方法
-----------------

Java 25 以上が必要です。Maven Central で公開しています。

```xml
<dependency>
<groupId>com.uchicom</groupId>
<artifactId>server</artifactId>
<version>0.3.0</version>
</dependency>
```

最小の実装例
-----------------

接続ごとの処理を `ServerProcess` で実装し、`ServerProcessFactory` で生成して、`Server` を起動します。

```java
import com.uchicom.server.MultiSocketServer;
import com.uchicom.server.ServerProcess;
import com.uchicom.util.Parameter;
import java.io.IOException;
import java.net.Socket;

public class EchoMain {

public static void main(String[] args) {
	// port, back は必須
	Parameter parameter = new Parameter(args);
	new MultiSocketServer(parameter, (param, socket) -> new EchoProcess(socket)).execute();
}

static class EchoProcess implements ServerProcess {
	private final Socket socket;
	private volatile long lastTime = System.currentTimeMillis();

	EchoProcess(Socket socket) {
	this.socket = socket;
	}

	@Override
	public long getLastTime() {
	return lastTime;
	}

	@Override
	public void forceClose() {
	try {
		socket.close();
	} catch (IOException e) {
		e.printStackTrace();
	}
	}

	@Override
	public void execute() {
	try (socket) {
		var in = socket.getInputStream();
		var out = socket.getOutputStream();
		int b;
		while ((b = in.read()) != -1) {
		lastTime = System.currentTimeMillis();
		out.write(b);
		out.flush();
		}
	} catch (IOException e) {
		// forceClose による切断も含む
	}
	}
}
}
```

`java EchoMain -port 8080 -back 50` のように起動します(`Parameter` は `com.uchicom.util` のライブラリで、`-キー 値` 形式の引数を解釈します)。
`execute()` は呼び出したスレッドをブロックし、別スレッドから `stop()` を呼ぶと停止します。

`ServerProcess#getLastTime()` は最終アクセス時刻(ミリ秒)を返します。アイドルタイムアウトの判定に使われるため、通信のたびに更新してください。

Parameter のキー一覧
-----------------

| キー | 必須 | 意味 |
|---|---|---|
| `port` | 必須 | 待ち受けるポート番号 |
| `back` | 必須 | 接続待ちキュー(backlog)の最大長 |
| `host` | 任意 | 待ち受けるアドレス(例: `127.0.0.1`)。未指定時は全インターフェースで待ち受ける(従来どおり) |
| `pool` | `PoolSocketServer` のみ必須 | スレッドプールのスレッド数 |
| `timeout` | 任意 | アイドルタイムアウト(ミリ秒)。未指定時は10000 |
| `queue` | 任意 | `PoolSocketServer` の実行待ち接続数の上限。未指定時は無制限(従来どおり)。上限を超えた接続は受け付け直後にクローズする |
| `queueTimeout` | 任意 | `PoolSocketServer` の実行待ちタイムアウト(ミリ秒)。未指定時は `timeout` の値(それも未指定なら10000) |
| `maxConnection` | 任意 | `MultiSocketServer` / `VirtualSocketServer` の最大同時接続数。未指定時は上限なし(従来どおり)。上限に達している間に受け付けた接続は、受け付け直後に `ServerProcess#forceClose()` でクローズし、既存の接続には影響しない |
| `ssl` | 任意 | 指定するとSSL(TLS)で待ち受ける。以下のキーを使用する |
| `keyStorePass` | `ssl` 時に必須 | キーストアのパスワード。未指定だとエラー |
| `keyStoreName` | `ssl` 時に必須 | キーストアのファイルパス。未指定だとエラー |
| `keyStoreType` | 任意 | キーストアの種類(例: `JKS`、`PKCS12`)。既定は `PKCS12` |
| `algorithm` | 任意 | `KeyManagerFactory` のアルゴリズム。既定は `KeyManagerFactory.getDefaultAlgorithm()` |
| `protocol` | 任意 | `SSLContext` のプロトコル。既定は `TLS` |
| `sslProtocol` | 任意 | 有効にする SSL プロトコル。複数指定は `:` 区切り。既定は `TLSv1.3:TLSv1.2` |

数値キーの有効範囲
-----------------

コンストラクタで形式(整数)と範囲を検証し、不正なキーをすべて列挙した `IllegalArgumentException` を投げます(サーバは起動しません)。

| キー | 有効範囲 |
|---|---|
| `port` | 0〜65535(0 は空きポートを自動割り当て) |
| `back` | 0以上 |
| `pool` | 1以上 |
| `queue` | 1以上 |
| `queueTimeout` | 1以上(ミリ秒) |
| `timeout` | 1以上(ミリ秒) |
| `maxConnection` | 1以上 |

SSL は `ssl`、`keyStoreName`、`keyStorePass` だけで有効にできます。`sslProtocol` を指定すると既定の有効プロトコルを上書きします。

Server の種類
-----------------

| Server | 処理方式 | 用途・特徴 |
|---|---|---|
| `SingleSocketServer` | 受付スレッドで1接続ずつ順番に処理 | 同時接続を1つに限定したい場合。処理中は他の接続が待たされる |
| `MultiSocketServer` | 接続ごとに新しいデーモンスレッドを生成 | 実装が単純。既定では接続数に上限がなく、多数接続ではスレッドが増える(`maxConnection` で制限可能) |
| `PoolSocketServer` | `pool` 個の固定スレッドプールで処理 | 同時処理数を制限したい場合。`pool` を超える接続はプールの空きを待つ |
| `VirtualSocketServer` | 接続ごとに仮想スレッドを生成 | 多数の接続をブロッキング I/O で扱いたい場合 |

### execute() の戻りと例外

- `stop()` による停止では、例外なく正常に return します。
- 起動の失敗(ポート使用中などの bind 失敗、SSL 設定の誤り、キーストアの不在・読み込み失敗など)や、実行中の想定外の異常終了では、原因を cause に持つ `IllegalStateException` を投げます(ERROR ログも出力します)。後始末(監視スレッドの停止など)は例外を投げる前に行われます。
- 利用者は `execute()` の例外を捕捉することで、異常終了と正常停止を区別できます。

### 停止の挙動

- `stop()` は `alive` を false にし、サーバソケットを閉じて `accept()` を中断させます。`execute()` はその後に戻ります。
- `stop()` は監視スレッドを停止した後、残っている接続をすべて `ServerProcess#forceClose()` で即時に強制クローズします(タイムアウトを待ちません)。`forceClose()` が例外を投げても、残りの接続のクローズは継続します。
- `PoolSocketServer` / `VirtualSocketServer` は `stop()` で `ExecutorService#shutdownNow()` を呼び、実行中のタスクを interrupt し、未着手のタスクを破棄します。
- `PoolSocketServer` のスレッドはデーモンスレッドです。`MultiSocketServer` の接続スレッドもデーモンスレッドのため、JVM は `stop()` 後に終了できます。

ログ
-----------------

エラーは JDK 標準の `System.Logger`(依存追加なし)で出力します。出力先・レベルは、利用側の `System.Logger` 実装(既定では `java.util.logging`、または SLF4J などへのブリッジ)の設定で制御できます。

- ロガー名: `com.uchicom.server`
- `ERROR`: 異常(サーバの異常終了、accept の失敗、`ServerProcessFactory` での生成失敗、`forceClose()` の例外など)
- `WARNING`: 接続の拒否・破棄(`maxConnection` 超過、`queue` 満杯、キュー待ちタイムアウト)
- `INFO`: accept / `ServerProcessFactory` での生成が連続失敗した後の復旧通知

accept が連続して失敗する場合(fd 枯渇など)は、ログが溢れないよう間引きます。

- 初回のみ、スタックトレース付きで出力します。
- 以降は 100 件ごとに、連続失敗の件数と直近の例外のみを出力します。
- accept が成功すると連続回数をリセットします(間引いた分がある場合は、`INFO` で復旧を通知します)。

`ServerProcessFactory` での生成失敗(`createServerProcess` の例外)も同様に間引きます(初回のみスタックトレース付き、以降は 100 件ごと、成功でリセットし復旧を `INFO` で通知)。

接続の拒否・破棄は、理由別に `WARNING` で出力します。件数が多い場合に溢れないよう、初回と累計 100 件ごと(件数付き)のみ出力します。

- `maxConnection` 超過: `connection rejected: maxConnection exceeded`
- `queue` 満杯(実行待ちの上限超過): `connection rejected: queue full or executor unavailable`
- キュー待ちタイムアウト: `connection discarded: queue wait timeout`
- `stop()` 後の拒否は想定内のためログしません。

アイドルタイムアウト
-----------------

最終アクセスから一定時間(ミリ秒)を超えた接続を、監視スレッドが `ServerProcess#forceClose()` で強制クローズします。
時間は `Parameter` の `timeout`(ミリ秒)で指定します。未指定時は10000(10秒)です。

| Server | タイムアウト |
|---|---|
| SingleSocketServer | 対象 |
| MultiSocketServer | 対象 |
| PoolSocketServer | 対象 |
| VirtualSocketServer | 対象 |

### 実行待ちの接続

`PoolSocketServer` でプールが満杯のとき、接続は実行待ちになります。待機中の接続は、アイドルタイムアウトの監視対象外です。
代わりに次の制限があります。

- `queue` を指定すると、実行待ちの接続数に上限を設けます。上限を超えた接続は、受け付けた直後に `ServerProcess#forceClose()` でクローズします。未指定時は無制限です。
- 実行待ちの時間が `queueTimeout`(未指定時は `timeout`)を超えた接続は、監視スレッドが実行待ちから取り除いて `forceClose()` でクローズします(判定間隔は約1秒)。

実行を開始して監視対象に登録した時刻が記録され、タイムアウトは `getLastTime()` と登録時刻の新しい方を基準に判定します。
そのため、待機時間が `timeout` を超えた接続が実行開始の直後に `forceClose()` されることはありません(`ServerProcess` 側で特別な対応は不要です)。
