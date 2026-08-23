package com.oryxos.core.tool;

/**
 * 统一的工具抽象:任何来源的工具(内置 / MCP / 插件)都被包装成这个形状, ReAct 循环因此不必关心工具来自哪里. 本节只交付供 Provider 格式适配器消费的 schema 面向
 * 接口;执行语义留到 Tool 能力课再落地。
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
   * ProfileContext 读取。
   *
   * @param argumentsJson 模型给出的参数 JSON 文本
   * @return 包装后的执行结果;失败以返回值形式表达,而不是抛异常
   */
  ToolResult execute(String argumentsJson);
}
