package com.oryxos.core.tool;

/**
 * 统一的工具抽象:任何来源的工具(内置 / MCP / 插件)都被包装成这个形状，ReAct 循环因此不必关心工具来源.保留 JSON 文本边界以兼容已有 Provider.
 *
 * @author OryxOS Contributors
 */
public interface OryxTool {

  /**
   * 返回唯一的工具名,即 Profile 的 {@code tools} 条目与 LLM 工具调用中引用的名字.
   *
   * @return 工具名
   */
  String getName();

  /**
   * 返回供人类/LLM 阅读的工具功能描述.
   *
   * @return 工具描述
   */
  String getDescription();

  /**
   * 返回描述该工具所接受参数的 JSON Schema.
   *
   * @return JSON Schema 文本
   */
  String getInputSchema();

  /**
   * 用模型给出的参数 JSON 执行工具. 执行永远由 ToolExecutor 驱动(唯一执行路径); 签名刻意不携带 Profile —— 需要当前 agent 配置的工具从
   * ProfileContext 读取.
   *
   * @param argumentsJson 模型给出的参数 JSON 文本
   * @return 包装后的执行结果；安全拒绝可抛出运行时异常，由唯一 ToolExecutor 转成失败并审计
   */
  ToolResult execute(String argumentsJson);
}
