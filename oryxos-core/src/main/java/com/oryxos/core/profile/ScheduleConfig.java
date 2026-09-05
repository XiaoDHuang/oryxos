package com.oryxos.core.profile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Profile 上一条定时规则的声明式配置(第 25 节). 四要素全为字符串、保持"配置即 Agent"——cron/时区的可解析性校验在 AgentScheduler 注册期进行(对齐
 * ProfileLoader "校验失败不阻断启动"的处置哲学),本记录不主动求值。
 *
 * @author OryxOS Contributors
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScheduleConfig(String id, String cron, String zone, String message) {}
