package com.oryxos.core.react;

import com.oryxos.core.profile.Profile;
import com.oryxos.core.tool.OryxTool;
import com.oryxos.core.tool.ToolResult;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * 唯一工具执行入口负责授权、有限重试与最终审计，适配器不能另起一条执行链.执行器本身不是扩展点， 来源扩展统一通过OryxTool完成.
 *
 * @author OryxOS Contributors
 */
public final class ToolExecutor {

  private static final Logger LOG = LoggerFactory.getLogger(ToolExecutor.class);
  private static final int MAX_RETRIES = 3;
  private static final long INITIAL_BACKOFF_MS = 100L;

  private final Map<String, OryxTool> toolTable;
  private final ToolInvocationAudit toolInvocationAudit;
  private final LongConsumer sleeper;
  private final LongSupplier clock;

  /** 表快照在启动装配完成后固定，请求之间不共享可变注册状态. */
  public ToolExecutor(Map<String, OryxTool> toolTable, ToolInvocationAudit toolInvocationAudit) {
    this(toolTable, toolInvocationAudit, ToolExecutor::pause, System::nanoTime);
  }

  ToolExecutor(
      Map<String, OryxTool> toolTable,
      ToolInvocationAudit audit,
      LongConsumer sleeper,
      LongSupplier clock) {
    this.toolTable = Map.copyOf(toolTable);
    this.toolInvocationAudit = Objects.requireNonNull(audit);
    this.sleeper = Objects.requireNonNull(sleeper);
    this.clock = Objects.requireNonNull(clock);
  }

  /** 每个逻辑调用只写一次最终审计，审计失败不能触发外部副作用重放. */
  public ToolResult execute(String sessionId, AssistantMessage.ToolCall call) {
    Objects.requireNonNull(call, "工具调用不能为空");
    Profile profile = ProfileContext.current();
    long startedAt = clock.getAsLong();
    ToolResult result;
    try {
      result = executeAuthorized(profile, call);
    } catch (RuntimeException exception) {
      result = ToolResult.fail(call.name(), "工具执行失败");
    }
    long elapsed = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(clock.getAsLong() - startedAt));
    try {
      toolInvocationAudit.record(
          sessionId,
          profile == null ? null : profile.name(),
          call.name(),
          call.arguments(),
          result.success(),
          result.content(),
          result.errorMessage(),
          elapsed);
    } catch (RuntimeException exception) {
      // 审计通道失效要可观测，但异常正文可能带数据库凭证或工具参数。
      LOG.error("工具最终审计写入失败，未重放工具；异常类别={}", exception.getClass().getSimpleName());
    }
    return result;
  }

  private ToolResult executeAuthorized(Profile profile, AssistantMessage.ToolCall call) {
    if (Thread.currentThread().isInterrupted()) {
      return ToolResult.fail(call.name(), "工具执行已中断");
    }
    if (profile == null) {
      return ToolResult.fail(call.name(), "缺少当前Profile，拒绝工具调用");
    }
    if (!profile.tools().contains(call.name())) {
      return ToolResult.fail(call.name(), "Profile未授权工具: " + call.name());
    }
    OryxTool tool = toolTable.get(call.name());
    if (tool == null) {
      return ToolResult.fail(call.name(), "未知工具: " + call.name());
    }
    for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
      ToolResult result = tool.execute(call.arguments());
      if (Thread.currentThread().isInterrupted()) {
        return ToolResult.fail(call.name(), "工具执行已中断");
      }
      if (result == null || !call.name().equals(result.toolName())) {
        return ToolResult.fail(call.name(), "工具返回了无效结果");
      }
      if (result.success() || !result.retryable() || attempt == MAX_RETRIES) {
        return result;
      }
      sleeper.accept(INITIAL_BACKOFF_MS << attempt);
      if (Thread.currentThread().isInterrupted()) {
        return ToolResult.fail(call.name(), "工具重试已中断");
      }
    }
    throw new IllegalStateException("工具重试状态异常");
  }

  private static void pause(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }
}
