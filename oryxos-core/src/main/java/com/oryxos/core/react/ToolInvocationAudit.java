package com.oryxos.core.react;

/**
 * 写入 {@code tool_invocations} 审计行的端口. 定义在 core,因为 ToolExecutor 住在这里而 JPA 仓库住在 storage;实现由 storage
 * 模块提供。口径与 llm_calls 相同:成功与失败都记录。
 *
 * @author OryxOS Contributors
 */
public interface ToolInvocationAudit {

  /**
   * 记录一次工具执行,成功与失败都记.
   *
   * @param sessionId 审计关联键
   * @param profileName 所属 profile
   * @param toolName 被执行的工具
   * @param parameters 模型给出的参数 JSON
   * @param success 执行是否成功
   * @param result 成功时的结果内容
   * @param errorMessage 失败时的原因
   * @param latencyMs 墙钟执行耗时
   */
  void record(
      String sessionId,
      String profileName,
      String toolName,
      String parameters,
      boolean success,
      String result,
      String errorMessage,
      long latencyMs);
}
