package com.oryxos.core.tool;

/**
 * Uniform tool abstraction: every tool source (builtin / MCP / plugin) is wrapped into this shape
 * so the ReAct loop never cares where a tool came from. This lesson delivers only the schema-facing
 * surface consumed by the Provider's format adapter; execution semantics arrive with the Tool
 * capability lesson.
 *
 * @author OryxOS Contributors
 */
public interface OryxTool {

  /**
   * Returns the unique tool name, as referenced by Profile {@code tools} entries and LLM tool
   * calls.
   *
   * @return the tool name
   */
  String getName();

  /**
   * Returns the human/LLM-readable description of what the tool does.
   *
   * @return the tool description
   */
  String getDescription();

  /**
   * Returns the JSON Schema describing the parameters this tool accepts.
   *
   * @return the JSON Schema text
   */
  String getInputSchema();
}
