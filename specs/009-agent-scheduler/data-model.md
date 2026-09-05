# Data Model: 定时任务（第三种触发源）

无新增 SQLite 表、无既有表结构变更。本特性的数据形态全部在 Profile 配置层。

## ScheduleConfig（新增，com.oryxos.core.profile）

Profile 上一条定时规则的强类型记录：

| 分量 | 类型 | 含义 | 校验（注册期，FR-008） |
|---|---|---|---|
| `id` | String | 规则 id；锁与日志的身份 | 非空白；全实例唯一，重复跳过 |
| `cron` | String | cron 表达式（Spring 六段式，含秒） | 非空白；`CronTrigger` 构造可解析，非法跳过 |
| `zone` | String | 时区标识（IANA，如 `Asia/Shanghai`） | 非空白；`ZoneId.of` 可识别，非法跳过 |
| `message` | String | 到点发给 Agent 的消息 | 非空白，缺失/空白跳过 |

- 序列化：Profile YAML `schedules:` 列表项，键全小写单词，无需 snake→camel 归一化特判。
- 未知键容忍：`@JsonIgnoreProperties(ignoreUnknown = true)`，与 Profile 其他子块一致。
- 构造归一：`null` 分量不豁免校验——注册期按上表处置；Profile 规范构造器只负责 `null` 列表→空列表。

YAML 示例：

```yaml
schedules:
  - id: morning-report
    cron: "0 0 9 * * *"
    zone: "Asia/Shanghai"
    message: "汇总昨天的 PR 评审进度，生成日报"
```

## Profile（修改）

- `schedules` 字段：`List<Map<String, Object>>` → `List<ScheduleConfig>`。其余字段不变。
- 规范构造器对 `schedules` 的 null→空表归一保持不变。

## 进程内控制状态（非持久化）

- **任务锁表**：`ConcurrentMap<String, Lock>`，键为规则 id（R7）；进程内易失，重启即空，非业务状态。
- **注册去重集**：`Set<String>` 已注册规则 id，仅在 registerAll 期间使用。

## 复用的既有持久化（零变更）

- **Session**：定时触发以 ("scheduler","scheduler",profileName) 三元组走既有 `SessionManager.getOrCreate` 幂等取会话，落 `sessions` 表；历史截断由既有 `max_history_turns` 兜底。
- **审计**：定时触发的 LLM/工具调用经 `AgentService.process` 既有路径落 `llm_calls`/`tool_invocations`，与人推无差别。

## 生命周期

- 规则随进程启动注册（`registerAll`），运行期不增删；改规则需重启（扩展阶段才补运行时增删接口）。
- 触发执行无状态机：拿锁→执行→放锁；跳过/失败均不留持久化残迹（失败痕迹在审计表，与人推同口径）。
