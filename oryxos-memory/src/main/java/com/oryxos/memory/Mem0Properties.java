package com.oryxos.memory;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 仅在选择内建Mem0后端时绑定远端地址、身份与总期限，Secret不进入字符串表示.
 *
 * @author OryxOS Contributors
 */
@ConfigurationProperties("memory.mem0")
public final class Mem0Properties {

  private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration DEFAULT_OPERATION_TIMEOUT = Duration.ofSeconds(40);
  private static final Duration MIN_OPERATION_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration MAX_OPERATION_TIMEOUT = Duration.ofSeconds(55);
  private static final int DEFAULT_HTTPS_PORT = 443;
  private static final int MAX_PORT = 65535;
  private static final int MAX_ASCII = 127;
  private static final int MAX_HOST_LENGTH = 253;
  private static final int TOKEN_BYTES = 32;
  private static final String PERCENT = "%";
  private static final String COLON = ":";
  private static final String DOT = ".";
  private static final String DOT_PATTERN = "\\.";
  private static final String OPEN_BRACKET = "[";
  private static final String CLOSE_BRACKET = "]";
  private static final String ROOT_PATH = "/";
  private static final Pattern WEB_SCHEME = Pattern.compile("https", Pattern.CASE_INSENSITIVE);
  private static final Pattern API_TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
  private static final Pattern DNS_LABEL =
      Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", Pattern.CASE_INSENSITIVE);
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final URI baseUrl;
  private final String apiKey;
  private final UUID workspaceId;
  private final Duration connectTimeout;
  private final Duration readTimeout;
  private final Duration operationTimeout;

  /**
   * 缺省只用于三个期限，地址、凭证与工作区身份没有云端或共享默认值.
   *
   * @param baseUrl 显式自托管HTTPS origin
   * @param apiKey 32随机字节的无padding base64url token
   * @param workspaceId 规范非nil工作区UUID
   * @param connectTimeout 建连上限，默认3秒
   * @param readTimeout 单次读取上限，默认30秒
   * @param operationTimeout 整个逻辑操作上限，默认40秒
   */
  public Mem0Properties(
      String baseUrl,
      String apiKey,
      String workspaceId,
      Duration connectTimeout,
      Duration readTimeout,
      Duration operationTimeout) {
    this.baseUrl = normalizeBaseUrl(baseUrl);
    this.apiKey = validateToken(apiKey);
    this.workspaceId = validateWorkspace(workspaceId);
    this.connectTimeout = defaultIfNull(connectTimeout, DEFAULT_CONNECT_TIMEOUT);
    this.readTimeout = defaultIfNull(readTimeout, DEFAULT_READ_TIMEOUT);
    this.operationTimeout = defaultIfNull(operationTimeout, DEFAULT_OPERATION_TIMEOUT);
    validateTimeouts(this.connectTimeout, this.readTimeout, this.operationTimeout);
  }

  /** 返回不带路径、凭证、查询或片段的规范HTTPS origin. */
  public URI baseUrl() {
    return baseUrl;
  }

  /** 返回仅供Authorization头使用的原始token，调用方不得记录. */
  public String apiKey() {
    return apiKey;
  }

  /** 返回与服务凭证绑定一致的工作区身份. */
  public UUID workspaceId() {
    return workspaceId;
  }

  /** 返回单次TCP/TLS建连上限. */
  public Duration connectTimeout() {
    return connectTimeout;
  }

  /** 返回单次响应读取上限. */
  public Duration readTimeout() {
    return readTimeout;
  }

  /** 返回包含PUT和状态确认查询的总期限. */
  public Duration operationTimeout() {
    return operationTimeout;
  }

  @Override
  public String toString() {
    return "Mem0Properties[baseUrl="
        + baseUrl
        + ", apiKey=<redacted>, workspaceId="
        + workspaceId
        + ", connectTimeout="
        + connectTimeout
        + ", readTimeout="
        + readTimeout
        + ", operationTimeout="
        + operationTimeout
        + "]";
  }

  private static URI normalizeBaseUrl(String value) {
    try {
      if (value == null || value.isBlank() || !value.equals(value.trim())) {
        throw invalid("memory.mem0.base-url无效");
      }
      URI input = URI.create(value);
      String scheme = input.getScheme();
      if (scheme == null || !WEB_SCHEME.matcher(scheme).matches()) {
        throw invalid("memory.mem0.base-url无效");
      }
      if (input.getRawUserInfo() != null) {
        throw invalid("memory.mem0.base-url无效");
      }
      if (input.getRawQuery() != null) {
        throw invalid("memory.mem0.base-url无效");
      }
      if (input.getRawFragment() != null) {
        throw invalid("memory.mem0.base-url无效");
      }
      String path = input.getRawPath();
      if (!validOriginPath(path)) {
        throw invalid("memory.mem0.base-url无效");
      }
      String host = normalizeHost(input.getHost());
      int port = input.getPort();
      if (port == 0 || port < -1 || port > MAX_PORT) {
        throw invalid("memory.mem0.base-url无效");
      }
      return new URI("https", null, host, port == DEFAULT_HTTPS_PORT ? -1 : port, null, null, null);
    } catch (IllegalArgumentException | URISyntaxException exception) {
      throw invalid("memory.mem0.base-url无效");
    }
  }

  private static String normalizeHost(String value) {
    if (value == null || value.isBlank() || value.contains(PERCENT)) {
      throw new IllegalArgumentException();
    }
    String host = stripBrackets(value);
    if (host.contains(COLON)) {
      try {
        InetAddress address = InetAddress.getByName(host);
        if (!(address instanceof Inet6Address)) {
          throw new IllegalArgumentException();
        }
        return address.getHostAddress();
      } catch (UnknownHostException exception) {
        throw new IllegalArgumentException();
      }
    }
    if (host.chars().anyMatch(character -> character > MAX_ASCII)) {
      throw new IllegalArgumentException();
    }
    String ascii = lowerAscii(host);
    if (ascii.endsWith(DOT)) {
      ascii = ascii.substring(0, ascii.length() - 1);
    }
    if (ascii.isEmpty() || ascii.length() > MAX_HOST_LENGTH) {
      throw new IllegalArgumentException();
    }
    for (String label : ascii.split(DOT_PATTERN, -1)) {
      if (!DNS_LABEL.matcher(label).matches()) {
        throw new IllegalArgumentException();
      }
    }
    return ascii;
  }

  private static String stripBrackets(String value) {
    if (value.startsWith(OPEN_BRACKET) && value.endsWith(CLOSE_BRACKET)) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  private static String lowerAscii(String value) {
    char[] characters = value.toCharArray();
    for (int index = 0; index < characters.length; index++) {
      char character = characters[index];
      if (character >= 'A' && character <= 'Z') {
        characters[index] = (char) (character + ('a' - 'A'));
      }
    }
    return new String(characters);
  }

  private static String validateToken(String value) {
    try {
      if (value == null || !API_TOKEN.matcher(value).matches()) {
        throw invalid("memory.mem0.api-key无效");
      }
      byte[] decoded = Base64.getUrlDecoder().decode(value + "=");
      try {
        String canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(decoded);
        if (decoded.length != TOKEN_BYTES || !canonical.equals(value)) {
          throw invalid("memory.mem0.api-key无效");
        }
      } finally {
        Arrays.fill(decoded, (byte) 0);
      }
      return value;
    } catch (IllegalArgumentException exception) {
      throw invalid("memory.mem0.api-key无效");
    }
  }

  private static UUID validateWorkspace(String value) {
    if (value == null) {
      throw invalid("memory.mem0.workspace-id无效");
    }
    try {
      UUID parsed = UUID.fromString(value);
      if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
        throw invalid("memory.mem0.workspace-id无效");
      }
      return parsed;
    } catch (IllegalArgumentException exception) {
      throw invalid("memory.mem0.workspace-id无效");
    }
  }

  private static Duration defaultIfNull(Duration value, Duration fallback) {
    return value == null ? fallback : value;
  }

  private static boolean validOriginPath(String path) {
    if (path == null) {
      return true;
    }
    return path.isEmpty() || ROOT_PATH.equals(path);
  }

  private static void validateTimeouts(Duration connect, Duration read, Duration operation) {
    boolean nonPositive =
        connect.isZero() || connect.isNegative() || read.isZero() || read.isNegative();
    boolean operationOutOfRange =
        operation.compareTo(MIN_OPERATION_TIMEOUT) < 0
            || operation.compareTo(MAX_OPERATION_TIMEOUT) > 0;
    if (nonPositive
        || operationOutOfRange
        || connect.compareTo(operation) > 0
        || read.compareTo(operation) > 0) {
      throw invalid("memory.mem0超时配置无效");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
