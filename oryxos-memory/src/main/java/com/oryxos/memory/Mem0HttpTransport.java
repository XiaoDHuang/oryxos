package com.oryxos.memory;

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_MULT_CHOICE;
import static java.net.HttpURLConnection.HTTP_OK;

import com.oryxos.memory.MemoryOperationException.Code;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

/** 单次同步HTTPS由受控虚拟线程承载，总期限同时约束慢速正文与资源回收. */
final class Mem0HttpTransport {
  private static final String HTTPS = "https";
  private static final String IDENTITY_ENCODING = "identity";
  private static final String JSON_MEDIA_TYPE = "application/json";
  private static final Pattern IDENTITY_HEADER =
      Pattern.compile(IDENTITY_ENCODING, Pattern.CASE_INSENSITIVE);
  private static final Pattern JSON_HEADER =
      Pattern.compile(JSON_MEDIA_TYPE, Pattern.CASE_INSENSITIVE);
  private static final int READ_BUFFER_BYTES = 8192;

  private final Mem0Properties properties;
  private final MemoryOutboundGuard guard;
  private final SSLContext tls;

  record Response(int status, byte[] body) {}

  Mem0HttpTransport(Mem0Properties properties, MemoryOutboundGuard guard, SSLContext tls) {
    if (properties == null || guard == null) {
      throw new MemoryOperationException(Code.MEMORY_INVALID_CONFIG);
    }
    this.properties = properties;
    this.guard = guard;
    this.tls = tls;
  }

  Response exchange(
      String method,
      String path,
      byte[] body,
      UUID operationId,
      long deadline,
      Runnable dispatched) {
    URI target = properties.baseUrl().resolve(path);
    if (!HTTPS.equals(target.getScheme())
        || !properties.baseUrl().getAuthority().equals(target.getAuthority())
        || !target.getPath().startsWith(Mem0Protocol.PREFIX + "/")
        || target.getRawFragment() != null) {
      throw new MemoryOperationException(Code.MEMORY_ACCESS_DENIED, operationId);
    }
    if (Thread.currentThread().isInterrupted() || remaining(deadline) <= 0) {
      throw new MemoryOperationException(Code.MEMORY_TIMEOUT, operationId);
    }
    try {
      guard.check(target);
    } catch (SecurityException exception) {
      throw new MemoryOperationException(Code.MEMORY_ACCESS_DENIED, operationId);
    }
    long requestDeadline =
        Math.min(
            deadline,
            System.nanoTime()
                + properties.connectTimeout().plus(properties.readTimeout()).toNanos());
    AtomicReference<HttpsURLConnection> active = new AtomicReference<>();
    FutureTask<Response> task =
        new FutureTask<>(() -> send(method, target, body, requestDeadline, active));
    // 只有本地校验与最终目标检查完成后，才把SAVE交给可能产生I/O的执行线程。
    dispatched.run();
    Thread.ofVirtual().name("oryx-memory-https").start(task);
    try {
      return task.get(Math.max(1, remaining(requestDeadline)), TimeUnit.NANOSECONDS);
    } catch (InterruptedException exception) {
      task.cancel(true);
      Thread.currentThread().interrupt();
      throw new MemoryOperationException(Code.MEMORY_TIMEOUT, operationId);
    } catch (TimeoutException exception) {
      task.cancel(true);
      throw new MemoryOperationException(Code.MEMORY_TIMEOUT, operationId);
    } catch (ExecutionException exception) {
      if (exception.getCause() instanceof MemoryOperationException memory) {
        throw new MemoryOperationException(memory.code(), operationId);
      }
      Code code =
          exception.getCause() instanceof SocketTimeoutException
              ? Code.MEMORY_TIMEOUT
              : Code.MEMORY_SERVICE_FAILURE;
      throw new MemoryOperationException(code, operationId);
    } finally {
      HttpsURLConnection connection = active.getAndSet(null);
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  @SuppressFBWarnings(
      value = "URLCONNECTION_SSRF_FD",
      justification = "唯一私有调用入口已验证固定HTTPS origin/路径并执行必需guard；禁代理、重定向与隐式TLS重连，拒绝用例有真实HTTPS验证")
  private Response send(
      String method,
      URI target,
      byte[] body,
      long deadline,
      AtomicReference<HttpsURLConnection> active)
      throws IOException {
    HttpsURLConnection connection =
        (HttpsURLConnection) target.toURL().openConnection(Proxy.NO_PROXY);
    active.set(connection);
    try {
      if (Thread.currentThread().isInterrupted() || remaining(deadline) <= 0) {
        throw new SocketTimeoutException();
      }
      SSLSocketFactory factory =
          tls == null ? (SSLSocketFactory) SSLSocketFactory.getDefault() : tls.getSocketFactory();
      connection.setSSLSocketFactory(new DirectTlsSocketFactory(factory));
      connection.setInstanceFollowRedirects(false);
      connection.setUseCaches(false);
      connection.setRequestMethod(method);
      connection.setConnectTimeout(milliseconds(properties.connectTimeout(), deadline));
      connection.setReadTimeout(milliseconds(properties.readTimeout(), deadline));
      connection.setRequestProperty("Authorization", "Bearer " + properties.apiKey());
      connection.setRequestProperty("Accept", JSON_MEDIA_TYPE);
      connection.setRequestProperty("Accept-Encoding", IDENTITY_ENCODING);
      if (body != null) {
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream output = connection.getOutputStream()) {
          output.write(body);
        }
      }
      int status = connection.getResponseCode();
      if (status >= HTTP_MULT_CHOICE && status < HTTP_BAD_REQUEST) {
        throw Mem0Protocol.invalid();
      }
      String encoding = connection.getHeaderField("Content-Encoding");
      if (encoding != null && !IDENTITY_HEADER.matcher(encoding).matches()) {
        throw Mem0Protocol.invalid();
      }
      String contentType = connection.getContentType();
      // 错误状态可以没有JSON正文，但不能以其文字推断SAVE已经回滚。
      boolean jsonResponse =
          contentType != null && JSON_HEADER.matcher(contentType.split(";", 2)[0].trim()).matches();
      boolean successful = status >= HTTP_OK && status < HTTP_MULT_CHOICE;
      if (successful && !jsonResponse) {
        throw Mem0Protocol.invalid();
      }
      if (connection.getContentLengthLong() > Mem0Protocol.MAX_RESPONSE_BYTES) {
        throw Mem0Protocol.invalid();
      }
      InputStream stream =
          status >= HTTP_BAD_REQUEST ? connection.getErrorStream() : connection.getInputStream();
      if (stream == null) {
        return new Response(status, new byte[0]);
      }
      try (InputStream input = stream;
          ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        int size;
        while ((size = input.read(buffer)) != -1) {
          if (remaining(deadline) <= 0) {
            throw new SocketTimeoutException();
          }
          int nextSize = output.size() + size;
          if (nextSize > Mem0Protocol.MAX_RESPONSE_BYTES) {
            throw Mem0Protocol.invalid();
          }
          output.write(buffer, 0, size);
        }
        return new Response(status, output.toByteArray());
      }
    } finally {
      active.compareAndSet(connection, null);
      connection.disconnect();
    }
  }

  static long deadline(Duration duration) {
    return System.nanoTime() + duration.toNanos();
  }

  static long remaining(long deadline) {
    return deadline - System.nanoTime();
  }

  private static int milliseconds(Duration budget, long deadline) {
    long nanos = Math.min(budget.toNanos(), Math.max(1, remaining(deadline)));
    return (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos));
  }

  /**
   * 保留SocketFactory未实现无参createSocket的标准行为，使JDK先用NO_PROXY建连再叠加TLS.
   * 原生SSL无参socket会继承SOCKS选择器；不能靠修改全局ProxySelector避开它。
   */
  private static final class DirectTlsSocketFactory extends SSLSocketFactory {
    private final SSLSocketFactory delegate;

    DirectTlsSocketFactory(SSLSocketFactory delegate) {
      this.delegate = delegate;
    }

    @Override
    public String[] getDefaultCipherSuites() {
      return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
      return delegate.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket(Socket socket, String host, int port, boolean autoClose)
        throws IOException {
      return delegate.createSocket(socket, host, port, autoClose);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
      throw new IOException("TLS只能复用受控直连，禁止隐式重连");
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress local, int localPort)
        throws IOException {
      throw new IOException("TLS只能复用受控直连，禁止隐式重连");
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
      throw new IOException("TLS只能复用受控直连，禁止隐式重连");
    }

    @Override
    public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort)
        throws IOException {
      throw new IOException("TLS只能复用受控直连，禁止隐式重连");
    }
  }
}
