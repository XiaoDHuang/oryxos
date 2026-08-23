# Data Model: ReAct 循环

## Session(oryxos-core,`com.oryxos.core.session.Session`,内存版)

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | String | 会话标识(本节由调用方给定;18 节起只在 SessionManager 内拼接) |
| `profileName` | String | 关联 Profile 名 |
| `messages` | List\<Message>(Spring AI) | 逐轮累积:UserMessage / AssistantMessage(含 toolCalls)/ ToolResponseMessage |

行为:`append(UserMessage)` / `append(AssistantMessage)` / `appendToolResult(ToolResponseMessage)`;`id()`/`profileName()`/`messages()` 只读。本节内存版,18 节升级 SQLite(sessions 表已在 schema.sql)。

## ToolResult(oryxos-core,`com.oryxos.core.tool.ToolResult`,record)

| 字段 | 类型 | 说明 |
|---|---|---|
| `toolName` | String | 工具名 |
| `success` | boolean | 成功标识 |
| `content` | String | 结果内容(成功) |
| `errorMessage` | String | 失败原因(失败),成功时 null |

静态工厂:`ok(toolName, content)` / `fail(toolName, errorMessage)`。

## ToolInvocation(oryxos-storage,`com.oryxos.storage.audit.ToolInvocation`,JPA)

对应 `tool_invocations` 表(本节补 `success`/`error_message` 两列):

| 列 | 类型 | 说明 |
|---|---|---|
| `invocation_id` | TEXT PK | UUID |
| `session_id` / `profile_name` | TEXT | 关联 |
| `tool_name` | TEXT NOT NULL | 工具名 |
| `parameters` | TEXT | 参数 JSON(模型给的 arguments 原文) |
| `status` | TEXT NOT NULL | completed/failed(既有列保留) |
| `result` / `error` | TEXT | 既有列保留 |
| `success` | INTEGER NOT NULL | **本节新增** |
| `error_message` | TEXT | **本节新增**,与 llm_calls 同口径 |
| `started_at` / `completed_at` | TEXT | ISO-8601 |
| `token_cost` | INTEGER | 既有列保留(本节不填) |

## ProfileContext(oryxos-core,`com.oryxos.core.react.ProfileContext`)

ThreadLocal<Profile>:`set(Profile)` / `current()`(无则 null)/ `clear()`。AgentService 入口 set、finally clear。

## 既有类型的本节消费点

- `Profile.settings().maxIterations()`(默认 10)→ ReActLoop 轮数上限
- `Profile.settings().maxHistoryTurns()`(默认 20)→ PromptBuilder 截断
- `Profile.bootstrap()` → ContextLoader 读 `.oryxos/` 根;`Profile.skills()` → 读 `.oryxos/skills/<name>/SKILL.md`
- `Profile.tools()` → PromptBuilder 按名从工具表筛可用工具
