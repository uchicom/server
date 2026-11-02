// (C) 2016 uchicom
package com.uchicom.server;

import com.uchicom.util.Parameter;
import java.io.FileInputStream;
import java.net.ServerSocket;
import java.security.KeyStore;
import java.security.SecureRandom;
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
  static final String DEFAULT_KEY_STORE_TYPE = "PKCS12";
  static final String DEFAULT_PROTOCOL = "TLS";
  static final String DEFAULT_SSL_PROTOCOL = "TLSv1.3:TLSv1.2";

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
    char[] pass = getRequired("keyStorePass").toCharArray();
    String keyStoreName = getRequired("keyStoreName");
    // キーストアのロード
    KeyStore ks = KeyStore.getInstance(getOrDefault("keyStoreType", DEFAULT_KEY_STORE_TYPE));
    try (FileInputStream fis = new FileInputStream(keyStoreName)) {
      ks.load(fis, pass);
    }
    KeyManagerFactory kmf =
        KeyManagerFactory.getInstance(
            getOrDefault("algorithm", KeyManagerFactory.getDefaultAlgorithm()));
    kmf.init(ks, pass);

    SSLContext sslContext = SSLContext.getInstance(getOrDefault("protocol", DEFAULT_PROTOCOL));
    sslContext.init(kmf.getKeyManagers(), null, new SecureRandom());

    SSLServerSocketFactory ssf = sslContext.getServerSocketFactory();
    SSLServerSocket sslServerSocket = (SSLServerSocket) ssf.createServerSocket();
    sslServerSocket.setEnabledProtocols(
        getOrDefault("sslProtocol", DEFAULT_SSL_PROTOCOL).split(":", 0));
    return sslServerSocket;
  }

  private String getRequired(String key) {
    if (!parameter.is(key)) {
      throw new IllegalArgumentException("SSL設定に必須のキーが指定されていません: " + key);
    }
    return parameter.get(key);
  }

  private String getOrDefault(String key, String defaultValue) {
    return parameter.is(key) ? parameter.get(key) : defaultValue;
  }
}
