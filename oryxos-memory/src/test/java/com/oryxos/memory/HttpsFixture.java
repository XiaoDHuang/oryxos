package com.oryxos.memory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import okhttp3.mockwebserver.MockWebServer;

/** 为后续远端适配测试提供不依赖生产信任设置的专属TLS端点. */
public final class HttpsFixture implements AutoCloseable {

  private static final long KEYTOOL_TIMEOUT_SECONDS = 20;
  private static final String CA_ALIAS = "oryx-test-ca";
  private static final String SERVER_ALIAS = "oryx-test-server";
  private static final String SAN = "SAN=dns:localhost,ip:127.0.0.1,ip:0:0:0:0:0:0:0:1";

  private final Path directory;
  private final char[] password;
  private final MockWebServer server;
  private final SSLContext clientSslContext;
  private final X509Certificate certificateAuthority;
  private final X509Certificate serverCertificate;
  private boolean closed;

  private HttpsFixture(
      Path directory,
      char[] password,
      MockWebServer server,
      SSLContext clientSslContext,
      X509Certificate certificateAuthority,
      X509Certificate serverCertificate) {
    this.directory = directory;
    this.password = password;
    this.server = server;
    this.clientSslContext = clientSslContext;
    this.certificateAuthority = certificateAuthority;
    this.serverCertificate = serverCertificate;
  }

  public static HttpsFixture open() {
    Path directory = null;
    char[] password = randomPassword();
    try {
      directory = Files.createTempDirectory("oryx-mem0-https-");
      Path keytool = keytoolPath();
      Path caStore = directory.resolve("ca.p12");
      Path caCertificate = directory.resolve("ca.pem");
      Path serverStore = directory.resolve("server.p12");
      Path serverRequest = directory.resolve("server.csr");
      Path signedServerCertificate = directory.resolve("server.pem");

      generateCertificates(
          keytool,
          password,
          caStore,
          caCertificate,
          serverStore,
          serverRequest,
          signedServerCertificate);

      KeyStore keyStore = loadKeyStore(serverStore, password);
      X509Certificate ca = readCertificate(caCertificate);
      X509Certificate leaf = (X509Certificate) keyStore.getCertificate(SERVER_ALIAS);
      SSLContext serverContext = serverContext(keyStore, password);
      SSLContext clientContext = clientContext(ca);

      MockWebServer server = new MockWebServer();
      server.useHttps(serverContext.getSocketFactory(), false);
      server.start();
      return new HttpsFixture(directory, password, server, clientContext, ca, leaf);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      deleteDirectory(directory);
      Arrays.fill(password, '\0');
      throw new IllegalStateException("临时TLS证书生成被中断", exception);
    } catch (Exception exception) {
      deleteDirectory(directory);
      Arrays.fill(password, '\0');
      throw new IllegalStateException("临时TLS测试端点创建失败", exception);
    }
  }

  public MockWebServer server() {
    ensureOpen();
    return server;
  }

  public SSLContext clientSslContext() {
    ensureOpen();
    return clientSslContext;
  }

  public X509Certificate certificateAuthority() {
    ensureOpen();
    return certificateAuthority;
  }

  X509Certificate serverCertificate() {
    ensureOpen();
    return serverCertificate;
  }

  public URI uri(String path) {
    ensureOpen();
    URI relative = URI.create(path);
    if (!path.startsWith("/")
        || relative.isAbsolute()
        || relative.getRawAuthority() != null
        || relative.getRawFragment() != null) {
      throw new IllegalArgumentException("测试HTTPS路径必须是无片段的绝对路径");
    }
    return server.url(path).uri();
  }

  Path workDirectory() {
    return directory;
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    RuntimeException failure = null;
    try {
      server.shutdown();
    } catch (IOException exception) {
      failure = new IllegalStateException("临时HTTPS服务关闭失败", exception);
    } finally {
      Arrays.fill(password, '\0');
      try {
        deleteDirectory(directory);
      } catch (RuntimeException exception) {
        if (failure == null) {
          failure = exception;
        } else {
          failure.addSuppressed(exception);
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  private static void generateCertificates(
      Path keytool,
      char[] password,
      Path caStore,
      Path caCertificate,
      Path serverStore,
      Path serverRequest,
      Path signedServerCertificate)
      throws IOException, InterruptedException {
    String secret = new String(password);
    runKeytool(
        keytool,
        "-genkeypair",
        "-alias",
        CA_ALIAS,
        "-keyalg",
        "RSA",
        "-keysize",
        "2048",
        "-sigalg",
        "SHA256withRSA",
        "-dname",
        "CN=OryxOS Test CA,O=OryxOS",
        "-ext",
        "bc=ca:true",
        "-ext",
        "ku=keyCertSign,cRLSign",
        "-validity",
        "2",
        "-storetype",
        "PKCS12",
        "-keystore",
        caStore.toString(),
        "-storepass",
        secret,
        "-keypass",
        secret,
        "-noprompt");
    runKeytool(
        keytool,
        "-exportcert",
        "-rfc",
        "-alias",
        CA_ALIAS,
        "-keystore",
        caStore.toString(),
        "-storepass",
        secret,
        "-file",
        caCertificate.toString());
    runKeytool(
        keytool,
        "-genkeypair",
        "-alias",
        SERVER_ALIAS,
        "-keyalg",
        "RSA",
        "-keysize",
        "2048",
        "-sigalg",
        "SHA256withRSA",
        "-dname",
        "CN=localhost,O=OryxOS",
        "-ext",
        SAN,
        "-validity",
        "2",
        "-storetype",
        "PKCS12",
        "-keystore",
        serverStore.toString(),
        "-storepass",
        secret,
        "-keypass",
        secret,
        "-noprompt");
    runKeytool(
        keytool,
        "-certreq",
        "-alias",
        SERVER_ALIAS,
        "-keystore",
        serverStore.toString(),
        "-storepass",
        secret,
        "-file",
        serverRequest.toString(),
        "-ext",
        SAN);
    runKeytool(
        keytool,
        "-gencert",
        "-rfc",
        "-alias",
        CA_ALIAS,
        "-keystore",
        caStore.toString(),
        "-storepass",
        secret,
        "-infile",
        serverRequest.toString(),
        "-outfile",
        signedServerCertificate.toString(),
        "-validity",
        "2",
        "-ext",
        "ku=digitalSignature,keyEncipherment",
        "-ext",
        "eku=serverAuth",
        "-ext",
        SAN);
    runKeytool(
        keytool,
        "-importcert",
        "-alias",
        CA_ALIAS,
        "-keystore",
        serverStore.toString(),
        "-storepass",
        secret,
        "-file",
        caCertificate.toString(),
        "-noprompt");
    runKeytool(
        keytool,
        "-importcert",
        "-alias",
        SERVER_ALIAS,
        "-keystore",
        serverStore.toString(),
        "-storepass",
        secret,
        "-file",
        signedServerCertificate.toString(),
        "-noprompt");
  }

  private static void runKeytool(Path keytool, String... arguments)
      throws IOException, InterruptedException {
    String[] command = new String[arguments.length + 1];
    command[0] = keytool.toString();
    System.arraycopy(arguments, 0, command, 1, arguments.length);
    Process process =
        new ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    if (!process.waitFor(KEYTOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
      throw new IllegalStateException("临时TLS证书生成超时");
    }
    if (process.exitValue() != 0) {
      throw new IllegalStateException("临时TLS证书生成失败");
    }
  }

  private static Path keytoolPath() {
    String executable =
        System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
    Path path = Path.of(System.getProperty("java.home"), "bin", executable);
    if (!Files.isRegularFile(path)) {
      throw new IllegalStateException("当前JDK缺少keytool");
    }
    return path;
  }

  private static KeyStore loadKeyStore(Path path, char[] password) throws Exception {
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    try (InputStream input = Files.newInputStream(path)) {
      keyStore.load(input, password);
    }
    return keyStore;
  }

  private static X509Certificate readCertificate(Path path) throws Exception {
    CertificateFactory factory = CertificateFactory.getInstance("X.509");
    try (InputStream input = Files.newInputStream(path)) {
      return (X509Certificate) factory.generateCertificate(input);
    }
  }

  private static SSLContext serverContext(KeyStore keyStore, char[] password) throws Exception {
    KeyManagerFactory factory =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    factory.init(keyStore, password);
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(factory.getKeyManagers(), null, null);
    return context;
  }

  private static SSLContext clientContext(X509Certificate ca) throws Exception {
    KeyStore trustStore = KeyStore.getInstance("PKCS12");
    trustStore.load(null, null);
    trustStore.setCertificateEntry(CA_ALIAS, ca);
    TrustManagerFactory factory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init(trustStore);
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, factory.getTrustManagers(), null);
    return context;
  }

  private static char[] randomPassword() {
    byte[] value = new byte[32];
    new SecureRandom().nextBytes(value);
    try {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(value).toCharArray();
    } finally {
      Arrays.fill(value, (byte) 0);
    }
  }

  private static void deleteDirectory(Path directory) {
    if (directory == null || !Files.exists(directory)) {
      return;
    }
    try (Stream<Path> files = Files.walk(directory)) {
      for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("临时TLS测试目录清理失败", exception);
    }
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("临时HTTPS测试端点已关闭");
    }
  }
}
