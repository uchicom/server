// (C) 2026 uchicom
package com.uchicom.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.uchicom.util.Parameter;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLServerSocket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ServerSocketFactoryTest {

  static final String PASS = "changeit";

  static Path dir;
  static Path keyStore;

  @BeforeAll
  static void createKeyStore() throws Exception {
    dir = Files.createDirectories(Path.of("target", "test-keystore"));
    keyStore = dir.resolve("test.p12");
    Files.deleteIfExists(keyStore);
    String keytool =
        Path.of(System.getProperty("java.home"), "bin", "keytool").toAbsolutePath().toString();
    Process process =
        new ProcessBuilder(
                keytool,
                "-genkeypair",
                "-alias",
                "test",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-storetype",
                "PKCS12",
                "-keystore",
                keyStore.toString(),
                "-storepass",
                PASS,
                "-dname",
                "CN=localhost",
                "-validity",
                "2")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
    assertEquals(true, process.waitFor(60, TimeUnit.SECONDS));
    assertEquals(0, process.exitValue());
    assertEquals(true, Files.exists(keyStore));
  }

  Parameter sslParameter() {
    Parameter parameter = new Parameter(new String[0]);
    parameter.put("ssl", "true");
    parameter.put("keyStorePass", PASS);
    parameter.put("keyStoreType", "PKCS12");
    parameter.put("keyStoreName", keyStore.toString());
    parameter.put("algorithm", "SunX509");
    parameter.put("protocol", "TLS");
    return parameter;
  }

  @Test
  void createPlainServerSocket() throws Exception {
    Parameter parameter = new Parameter(new String[0]);
    try (ServerSocket socket = new ServerSocketFactory(parameter).createServerSocket()) {
      assertFalse(socket instanceof SSLServerSocket);
    }
  }

  @Test
  void createSSLServerSocket() throws Exception {
    try (ServerSocket socket = new ServerSocketFactory(sslParameter()).createServerSocket()) {
      assertInstanceOf(SSLServerSocket.class, socket);
    }
  }

  @Test
  void createSSLServerSocketWithProtocol() throws Exception {
    Parameter parameter = sslParameter();
    parameter.put("sslProtocol", "TLSv1.2:TLSv1.3");
    try (ServerSocket socket = new ServerSocketFactory(parameter).createServerSocket()) {
      assertArrayEquals(
          new String[] {"TLSv1.2", "TLSv1.3"}, ((SSLServerSocket) socket).getEnabledProtocols());
    }
  }

  @Test
  void missingKeyStoreFails() {
    Parameter parameter = sslParameter();
    parameter.put("keyStoreName", dir.resolve("none.p12").toString());
    assertThrows(Exception.class, () -> new ServerSocketFactory(parameter).createServerSocket());
  }
}
