# Data Model: Agent Provider

## Profile(oryxos-core,`com.oryxos.core.profile.Profile`,record)

承载一个 Agent 的完整配置,对应 `.oryxos/profiles/*.yaml`。字段与 AGENTS.md 数据模型 + 课件 16 节逐项对齐:

| 字段 | 类型 | 说明 |
|---|---|---|
| `name` | String(必填) | Profile 名,唯一索引键 |
| `description` | String | 描述 |
| `identity` | Identity{`agentName`, `prompt`, `promptFile`} | 身份;prompt 与 promptFile 二选一 |
| `provider` | Provider{`name`(必填), `model`(必填), `temperature`(Double), `fallback`(String,预留不实现)} | 供应商选用 |
| `tools` | List\<String> | 启用的工具名 |
| `skills` | List\<String> | 引用的 SKILL.md |
| `mcpServers` | List\<String> | 引用的 MCP server(YAML 键 `mcp_servers`) |
| `channels` | List\<String> | 接入渠道 |
| `notifyChannels` | List\<Map\<String, Object>> | 通知渠道(YAML 键 `notify_channels`,每项含 `type` + 渠道特定配置如 `url`;结构由 19 节 NotifyTools 消费时定形) |
| `schedules` | List\<Schedule> | 定时配置(25 节消费) |
| `bootstrap` | List\<String> | Bootstrap 文件列表 |
| `settings` | Settings{`maxIterations`=10, `maxHistoryTurns`=20} | 运行参数,带默认值 |
| `createdAt` / `updatedAt` | String | 时间戳(YAML 键 `created_at`/`updated_at`) |

校验规则(本节只实施一条):`provider.name` 必须能在全局层 `oryxos.providers` 找到同名项,找不到 → 记错误日志、跳过该 Profile、不阻断启动。其余字段的校验规则由后续各节自补。

## LlmCall(oryxos-storage,`com.oryxos.storage.audit.LlmCall`,JPA 实体)

对应 `llm_calls` 表(手工脚本建表):

| 列 | 类型 | 说明 |
|---|---|---|
| `call_id` | TEXT PK | 调用标识(UUID) |
| `session_id` | TEXT | 关联会话 |
| `provider` / `model` | TEXT | 供应商名 / 模型名 |
| `prompt_tokens` / `completion_tokens` / `total_tokens` | INTEGER | token 用量(失败调用可为空) |
| `latency_ms` | INTEGER | 耗时 |
| `success` | INTEGER(0/1) NOT NULL | **本节新增**:成功标识 |
| `error_message` | TEXT | **本节新增**:失败原因(成功时为 NULL) |
| `status` | TEXT | 既有列保留:completed/failed |
| `started_at` / `completed_at` | TEXT | 起止时间(ISO-8601) |

状态机:`success=true` ↔ `status=completed`;`success=false` ↔ `status=failed` + `error_message` 非空。

## 全局层配置(oryxos-provider,`ProviderProperties`)

```yaml
oryxos:
  providers:
    - name: deepseek          # 唯一名,显式映射键
      api-key: ${DEEPSEEK_API_KEY}   # 环境变量占位
      base-url: ${DEEPSEEK_BASE_URL:https://api.deepseek.com}  # 可选,有默认
```

关系:`Profile.provider.name` →(引用)→ `ProviderProperties.providers[].name` →(构建期)→ `Map<String, ChatModel>` 的一项。
