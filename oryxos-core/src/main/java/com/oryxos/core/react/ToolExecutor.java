package com.oryxos.core.react;

import com.oryxos.core.profile.Profile;
import com.oryxos.core.tool.OryxTool;
import com.oryxos.core.tool.ToolResult;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * 工具执行的唯一地点. 按名字在工具表中查到被请求的工具、运行它、把结果包装成 {@link ToolResult},并把成功与失败都审计进 {@code tool_invocations} ——
 * 绝不静默吞异常,也绝不 让失败的工具砸掉循环(错误文本会喂回去,让模型下一轮看到)。
 *
 * @author OryxOS Contributors
 */
public class ToolExecutor {

  private final Map<String, OryxTool> toolTable;

  private final ToolInvocationAudit toolInvocationAudit;

  /** 在工具表(名称 → 工具)之上创建带审计端口的执行器. */
  public ToolExecutor(Map<String, OryxTool> toolTable, ToolInvocationAudit toolInvocationAudit) {
    this.toolTable = Map.copyOf(toolTable);
    this.toolInvocationAudit = toolInvocationAudit;
  }

  /** 执行一次模型请求的工具调用;失败以结果形式返回,不抛异常. */
  public ToolResult execute(String sessionId, AssistantMessage.ToolCall call) {
    String profileName = currentProfileName();
    OryxTool tool = toolTable.get(call.name());
    if (tool == null) {
      ToolResult result = ToolResult.fail(call.name(), "未知工具: " + call.name());
      toolInvocationAudit.record(
          sessionId,
          profileName,
          call.name(),
          call.arguments(),
          false,
          null,
          result.errorMessage(),
          0L);
      return result;
    }
    long startedAt = System.currentTimeMillis();
    try {
      // SANDBOX-SEAM:Sandbox.enforce(SandboxAction) 放在这里 —— 沙箱课(24 节)接线。
      // 在那之前,白名单校验位只标记、不实现。
      ToolResult result = tool.execute(call.arguments());
      toolInvocationAudit.record(
          sessionId,
          profileName,
          call.name(),
          call.arguments(),
          true,
          result.content(),
          null,
          System.currentTimeMillis() - startedAt);
      return result;
    } catch (RuntimeException e) {
      ToolResult result = ToolResult.fail(call.name(), e.getMessage());
      toolInvocationAudit.record(
          sessionId,
          profileName,
          call.name(),
          call.arguments(),
          false,
          null,
          e.getMessage(),
          System.currentTimeMillis() - startedAt);
      return result;
    }
  }

  private static String currentProfileName() {
    Profile profile = ProfileContext.current();
    return profile == null ? null : profile.name();
  }
}
