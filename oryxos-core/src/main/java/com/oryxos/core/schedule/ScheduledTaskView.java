package com.oryxos.core.schedule;

import java.time.Instant;

/**
 * 定时任务的只读视图,与 {@code scheduled_tasks} 行一一对应;lastStatus 取值 running/success/
 * failed/timeout/unknown,从未开始为 null;Store 返回的是持久化值,当前可运行性(available) 由调度器 catalog 派生,不在此视图落库.
 *
 * @author OryxOS Contributors
 */
public record ScheduledTaskView(
    String taskId,
    String profileName,
    String cron,
    String zone,
    String message,
    boolean enabled,
    Instant nextRunAt,
    Instant lastRunAt,
    String lastStatus,
    long runCount) {}
