package com.oryxos.tool.mcp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置只在Tool模块内流转，不能把SDK启动细节扩散到core.
 *
 * @author OryxOS Contributors
 */
record McpServerConfig(
    String name, String transport, List<String> command, Map<String, String> env) {
  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
  private static final Pattern SENSITIVE_ENV_NAME =
      Pattern.compile("(?i).*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|AUTH).*");
  private static final String COMMAND_FIELD = "command";
  private static final String STDIO = "stdio";

  McpServerConfig {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("MCP服务名称无效");
    }
    if (!STDIO.equals(transport)) {
      throw new IllegalArgumentException("MCP只支持stdio传输");
    }
    if (command == null || command.isEmpty()) {
      throw new IllegalArgumentException("MCP启动命令不能为空");
    }
    if (command.stream().anyMatch(value -> value == null || value.isBlank())) {
      throw new IllegalArgumentException("MCP命令参数无效");
    }
    if (env == null) {
      throw new IllegalArgumentException("MCP环境配置不能为空引用");
    }
    command = List.copyOf(command);
    env = Map.copyOf(env);
  }

  static McpServerConfig from(Object raw, Map<String, String> environment) {
    if (!(raw instanceof Map<?, ?> values)
        || !(values.get(COMMAND_FIELD) instanceof List<?> argv)) {
      throw new IllegalArgumentException("MCP配置必须包含argv列表");
    }
    List<String> command = new ArrayList<>();
    for (Object item : argv) {
      command.add(text(item));
    }
    Map<String, String> env = new LinkedHashMap<>();
    Object configured = values.get("env");
    if (configured != null) {
      if (!(configured instanceof Map<?, ?> variables)) {
        throw new IllegalArgumentException("MCP环境配置必须为键值对象");
      }
      for (var variable : variables.entrySet()) {
        String key = text(variable.getKey());
        if (key.isBlank() || key.contains("=") || key.indexOf('\0') >= 0) {
          throw new IllegalArgumentException("MCP环境变量名称无效");
        }
        String value = text(variable.getValue());
        Matcher matcher = PLACEHOLDER.matcher(value);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
          String replacement = environment.get(matcher.group(1));
          if (replacement == null) {
            throw new IllegalArgumentException("MCP环境占位符未配置");
          }
          matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);
        if (resolved.indexOf("${") >= 0 || resolved.indexOf("\0") >= 0) {
          throw new IllegalArgumentException("MCP环境值无效或占位符未解析");
        }
        env.put(key, resolved.toString());
      }
    }
    return new McpServerConfig(
        text(values.get("name")), text(values.get("transport")), command, env);
  }

  Set<String> sensitiveValues() {
    Set<String> values = new HashSet<>(env.size());
    for (var entry : env.entrySet()) {
      if (SENSITIVE_ENV_NAME.matcher(entry.getKey()).matches() && !entry.getValue().isBlank()) {
        values.add(entry.getValue());
      }
    }
    return Set.copyOf(values);
  }

  private static String text(Object value) {
    if (!(value instanceof String text)) {
      throw new IllegalArgumentException("MCP配置字段必须为字符串");
    }
    return text;
  }
}
