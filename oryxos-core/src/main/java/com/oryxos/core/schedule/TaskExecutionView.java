package com.oryxos.core.schedule;

import java.time.Instant;

/**
 * 一次定时执行的只读历史视图,与 {@code task_executions} 行一一对应;success 为 null 表示进行中, false 且 durationMs 为 null
 * 表示进程中断结果未知,不能用虚假的零耗时占位.
 *
 * @author OryxOS Contributors
 */
public record TaskExecutionView(
    long executionId,
    String taskId,
    String sessionId,
    Instant startedAt,
    Boolean success,
    String errorMessage,
    Long durationMs) {}
