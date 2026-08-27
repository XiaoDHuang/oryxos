package com.oryxos.tool.builtin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.oryxos.core.tool.ToolResult;
import com.oryxos.tool.sandbox.ActionType;
import com.oryxos.tool.sandbox.Sandbox;
import com.oryxos.tool.sandbox.SandboxAction;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * HTTP副作用在统一安全检查后执行，只有可安全重放的GET瞬态失败才声明重试.
 *
 * @author OryxOS Contributors
 */
public final class HttpTools {
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
  static final int MAX_RESPONSE_BYTES = 1024 * 1024;
  private static final Pattern WEB_SCHEME = Pattern.compile("https?", Pattern.CASE_INSENSITIVE);

  private final Sandbox sandbox;
  private final RestClient client;
  private final ObjectMapper mapper =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

  /** 客户端限制只作用于工具自身，避免修改Provider共享Builder. */
  public HttpTools(Sandbox sandbox, RestClient.Builder builder) {
    this(sandbox, builder, CONNECT_TIMEOUT, READ_TIMEOUT);
  }

  HttpTools(Sandbox sandbox, RestClient.Builder builder, Duration connect, Duration read) {
    this.sandbox = Objects.requireNonNull(sandbox);
    SimpleClientHttpRequestFactory factory =
        new SimpleClientHttpRequestFactory() {
          @Override
          protected void prepareConnection(HttpURLConnection connection, String method)
              throws IOException {
            super.prepareConnection(connection, method);
            // 跳转目标尚未过域名检查，不能让底层客户端自动跟随。
            connection.setInstanceFollowRedirects(false);
          }
        };
    factory.setConnectTimeout(connect);
    factory.setReadTimeout(read);
    client = builder.clone().requestFactory(factory).build();
  }

  /** 只有GET的瞬态故障能够声明可安全重放. */
  @Tool(name = "http_get", description = "发送HTTP GET请求并读取响应")
  public ToolResult httpGet(@ToolParam(description = "完整HTTP或HTTPS地址") String url) {
    return request(url, null, true);
  }

  /** POST可能已经产生副作用，不能根据失败状态自动重放. */
  @Tool(name = "http_post", description = "发送JSON格式的HTTP POST请求")
  public ToolResult httpPost(
      @ToolParam(description = "完整HTTP或HTTPS地址") String url,
      @ToolParam(description = "合法JSON文本") String body) {
    try {
      if (body == null || body.isBlank() || mapper.readTree(body).isMissingNode()) {
        return ToolResult.fail("http_post", "请求体必须是合法JSON");
      }
    } catch (JsonProcessingException exception) {
      return ToolResult.fail("http_post", "请求体必须是合法JSON");
    }
    return request(url, body, false);
  }

  private ToolResult request(String url, String body, boolean get) {
    String name = get ? "http_get" : "http_post";
    URI uri;
    try {
      uri = validateUrl(url);
    } catch (IllegalArgumentException exception) {
      return ToolResult.fail(name, "请求地址无效，仅允许无凭证的HTTP或HTTPS地址");
    }
    sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, uri.toString()));
    try {
      RestClient.RequestHeadersSpec<?> request =
          get
              ? client.get().uri(uri)
              : client.post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(body);
      return request.exchange(
          (sent, response) -> {
            var status = response.getStatusCode();
            if (!status.is2xxSuccessful()) {
              return ToolResult.fail(
                  name, "HTTP请求失败，状态码=" + status.value(), get && status.is5xxServerError());
            }
            // 多读一个字节只用于判断越界，永远不先读取完整未知长度的响应。
            byte[] bytes = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
              return ToolResult.fail(name, "HTTP响应超过1MiB上限");
            }
            return ToolResult.ok(name, new String(bytes, StandardCharsets.UTF_8));
          });
    } catch (ResourceAccessException exception) {
      return ToolResult.fail(name, "HTTP连接或读取失败", get && !Thread.currentThread().isInterrupted());
    } catch (RestClientException exception) {
      return ToolResult.fail(name, "HTTP请求处理失败");
    }
  }

  static URI validateUrl(String url) {
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("请求地址不能为空");
    }
    URI uri = URI.create(url);
    // URI协议按ASCII大小写匹配，不引入Unicode等价字符的安全歧义。
    boolean supportedScheme =
        uri.getScheme() != null && WEB_SCHEME.matcher(uri.getScheme()).matches();
    boolean safeAuthority = uri.getHost() != null && uri.getRawUserInfo() == null;
    if (!supportedScheme || !safeAuthority) {
      throw new IllegalArgumentException("请求地址格式不受支持");
    }
    return uri;
  }
}
