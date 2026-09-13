# Phase 1 Data Model: 插件化 Agent 目录

本节无数据库变更（无新表、无列、无迁移）。全部"数据"是文件系统目录与进程内对象。

## AgentDefinition（新增，core.agent，解析结果载体）

| 字段 | 类型 | 含义 | 校验 |
|---|---|---|---|
| `agentDir` | Path | Agent 目录绝对路径 | 必存在且为目录 |
| `frontmatter` | Map<String, Object> | `AGENT.md` 的 `---` 围栏内 YAML（键已 camelCase 归一化、`${ENV}` 已解析） | 可为空 Map，但派生时必填键缺失由 ProfileValidator 点名 |
| `body` | String | `AGENT.md` 去掉 frontmatter 后的正文（任务指令） | 允许为空（空正文=只有人格的助手骨架） |
| `scriptsDir` | Path | `<dir>/scripts` | 可选，不存在记 null |
| `skillsDir` | Path | `<dir>/skills` | 可选，不存在记 null |
| `referenceFile` | Path | `<dir>/REFERENCE.md` | 可选，不存在记 null |

资源字段只记录"位置"，不读内容——渐进式披露守点：内容只经底座 `read_file`/`shell` 按需进上下文。

## frontmatter → Profile 映射（派生规则，逐键一一对应）

| frontmatter 键（snake_case） | Profile 字段 | 说明 |
|---|---|---|
| `name` | `name` | 必填；课件约定应等于目录名，不等时以 frontmatter 为准、不额外造冲突策略 |
| `description` | `description` | 可选 |
| `identity.agent_name` | `identity.agentName` | 可选 |
| `identity.prompt` | `identity.prompt` | 人格，内联进 system prompt 首段（既有行为） |
| —（派生注入） | `identity.promptFile` | 固定置为 `agents/<目录名>/AGENT.md`，正文经 ContextLoader 现读注入 |
| `provider.{name,model,temperature,fallback}` | `provider.*` | `provider.name` 必填且在全局 provider 名集合内 |
| `tools` | `tools` | 可选；未注册能力名加载告警不阻断 |
| `skills` | `skills` | 可选（既有 Skill 引用语义不变，与 Agent 内部 `skills/` 子指令是两回事） |
| `mcp_servers` / `channels` / `bootstrap` / `settings` / `created_at` / `updated_at` | 同名字段 | 与手写 YAML 同构，照常映射 |
| `notify_channels` | `notifyChannels` | List<Map>，NotifyTools 消费（19 节） |
| `schedules` | `schedules` | List<ScheduleConfig>，原样带进派生 Profile（DeriveProfileTest 守点） |

未知键：`Profile` 上有 `@JsonIgnoreProperties(ignoreUnknown = true)`，与手写路径一致地忽略。

## 运行时状态（全部复用既有）

- `ProfileRegistry`：进程内保序 Map，新增可变写路径（register/remove/exists）；持久化无（重启重扫）。
- `AgentScheduler.cronFutures`：按任务 id 的 `ScheduledFuture` 句柄表（011 已交付，30 节注销用）。
- `scheduled_tasks`/`task_executions`：011 既有表，定义/状态/历史照常落库，零改动。

## 状态流转

- Agent 定义生命周期（本节）：`目录落盘 → 扫描解析 → 派生 → 校验 → 注册（可见）`；运行时注册同径。摘除：`remove(name)` 仅从注册表移除（定时句柄的注销是 30 节范围）。
- 定时规则生命周期：完全沿用 011（register→candidate→begin→终态唯一），无变化。
