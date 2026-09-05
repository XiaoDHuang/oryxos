package com.oryxos.core.session;

/**
 * 会话的只读摘要视图(列表与详情用),与实体列一一对应;运行时 {@link Session} 只持有活消息, 摘要额外携带状态与时间戳,供 Web 列表/详情端点外发.
 *
 * @author OryxOS Contributors
 */
public record SessionSummary(
    String sessionId,
    String profileName,
    String channel,
    String userId,
    String status,
    String createdAt,
    String lastActiveAt,
    String archivedAt) {}
