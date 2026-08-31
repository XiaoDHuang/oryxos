package com.oryxos.memory;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 只绑定选择器,避免本地后端提前解析远端密钥.
 *
 * @author OryxOS Contributors
 */
@ConfigurationProperties("memory")
public record MemoryProperties(String backend) {

  private static final String DEFAULT_BACKEND = "markdown";
  private static final Set<String> BACKENDS = Set.of(DEFAULT_BACKEND, "sqlite", "mem0");

  /** 非法选择不得静默降级,即使门面由嵌入方替换也校验. */
  public MemoryProperties {
    backend = backend == null ? DEFAULT_BACKEND : backend;
    if (!BACKENDS.contains(backend)) {
      throw new IllegalArgumentException("memory.backend只允许markdown、sqlite或mem0");
    }
  }
}
