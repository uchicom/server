// (C) 2026 uchicom
package com.uchicom.server;

import com.uchicom.util.Parameter;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** テスト用のServerProcessFactory.1バイトをエコーして切断する. */
class TestServerProcessFactory implements ServerProcessFactory {

  final AtomicInteger executed = new AtomicInteger();
  final CountDownLatch forceClosed = new CountDownLatch(1);
  final CountDownLatch release = new CountDownLatch(1);
  final boolean block;
  final long lastTime;

  TestServerProcessFactory() {
    this(false, System.currentTimeMillis());
  }

  TestServerProcessFactory(boolean block, long lastTime) {
    this.block = block;
    this.lastTime = lastTime;
  }

  @Override
  public ServerProcess createServerProcess(Parameter parameter, Socket socket) {
    return new ServerProcess() {
      @Override
      public long getLastTime() {
        return lastTime;
      }

      @Override
      public void forceClose() {
        forceClosed.countDown();
        release.countDown();
      }

      @Override
      public void execute() {
        try (socket) {
          if (block) {
            release.await();
            return;
          }
          InputStream in = socket.getInputStream();
          OutputStream out = socket.getOutputStream();
          int b = in.read();
          executed.incrementAndGet();
          out.write(b);
          out.flush();
        } catch (Exception e) {
          // テストでは無視する
        }
      }
    };
  }
}
