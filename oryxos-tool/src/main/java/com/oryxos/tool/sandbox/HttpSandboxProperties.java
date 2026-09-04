package com.oryxos.tool.sandbox;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 键名由技术方案固定为http.allowed_domains,精确匹配语义由007既有实现承载.
 *
 * @author OryxOS Contributors
 */
@ConfigurationProperties("http")
public record HttpSandboxProperties(List<String> allowedDomains) {

  /** 缺省绑定为空名单,由白名单实现保证空等于全拒绝而非不校验. */
  public HttpSandboxProperties {
    allowedDomains = allowedDomains == null ? List.of() : List.copyOf(allowedDomains);
  }
}
