package com.oryxos.tool.sandbox;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 键名由技术方案固定为file.allowed_paths,缺省视为空名单即全拒绝.
 *
 * @author OryxOS Contributors
 */
@ConfigurationProperties("file")
public record FileSandboxProperties(List<String> allowedPaths) {

  /** 缺省绑定为空名单,由白名单实现保证空等于全拒绝而非不校验. */
  public FileSandboxProperties {
    allowedPaths = allowedPaths == null ? List.of() : List.copyOf(allowedPaths);
  }
}
