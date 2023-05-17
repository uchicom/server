// (C) 2016 uchicom
package com.uchicom.server;

import com.uchicom.util.Parameter;
import java.io.FileInputStream;
import java.net.ServerSocket;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;

/**
 * サーバソケットファクトリークラス.
 *
 * @author uchicom: Shigeki Uchiyama
 */
public class ServerSocketFactory {
  protected Parameter parameter;

  public ServerSocketFactory(Parameter parameter) {
    this.parameter = parameter;
  }

  public ServerSocket createServerSocket() throws Exception {
    if (parameter.is("ssl")) {
      return createSSLServerSocket();
    } else {
      return new ServerSocket();
    }
  }

  private ServerSocket createSSLServerSocket() throws Exception {

    // パスワード
    char[] pass = parameter.get("keyStorePass").toCharArray();
    // キーストアのロード
    KeyStore ks = KeyStore.getInstance(parameter.get("keyStoreType"));
    ks.load(new FileInputStream(parameter.get("keyStoreName")), pass);
    KeyManagerFactory kmf = KeyManagerFactory.getInstance(parameter.get("algorithm"));
    kmf.init(ks, pass);

    SSLContext sslContext = SSLContext.getInstance(parameter.get("protocol"));
    sslContext.init(kmf.getKeyManagers(), null, null);

    SSLServerSocketFactory ssf = sslContext.getServerSocketFactory();
    SSLServerSocket sslServerSocket = (SSLServerSocket) ssf.createServerSocket();
    sslServerSocket.setEnabledProtocols(new String[] {"TLSv1.2"});
    return sslServerSocket;
  }
}
