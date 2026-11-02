server
=================


[![Maven Central](https://img.shields.io/maven-central/v/com.uchicom/server.svg)](http://search.maven.org/#search|ga|1|com.uchicom.server)
[![Maven Central](https://img.shields.io/github/license/uchicom/server.svg)](http://www.apache.org/licenses/LICENSE-2.0.txt)
いろいろなサーバプログラムの基本となる抽象サーバ

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
| SelectorServer | **対象外** |

`SelectorServer` は接続ごとの `ServerProcess` がなく、`Handler` が非同期に処理を進めるため、アイドル判定の対象外です。
