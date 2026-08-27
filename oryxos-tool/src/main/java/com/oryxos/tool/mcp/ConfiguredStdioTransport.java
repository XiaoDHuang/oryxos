package com.oryxos.tool.mcp;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;

/**
 * 子进程只继承SDK最小系统环境与已声明变量，不能继承宿主全部凭证.
 *
 * @author OryxOS Contributors
 */
final class ConfiguredStdioTransport extends StdioClientTransport {
  ConfiguredStdioTransport(ServerParameters parameters, McpJsonMapper mapper) {
    super(parameters, mapper);
  }

  @Override
  protected ProcessBuilder getProcessBuilder() {
    ProcessBuilder builder = super.getProcessBuilder();
    // SDK随后写入ServerParameters中的最小环境，先去掉ProcessBuilder的全量宿主继承。
    builder.environment().clear();
    return builder;
  }
}
