package com.oryxos.tool.sandbox;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 键名由技术方案固定为shell.allowed_commands,按命令首token精确比对.
 *
 * @author OryxOS Contributors
 */
@ConfigurationProperties("shell")
public record ShellSandboxProperties(List<String> allowedCommands) {

  /** 缺省绑定为空名单,由白名单实现保证空等于全拒绝而非不校验. */
  public ShellSandboxProperties {
    allowedCommands = allowedCommands == null ? List.of() : List.copyOf(allowedCommands);
  }
}
