# Phase 1 Data Model: 010 Web Service 与第一版管理平台

**无新数据表、无表结构变更。** sessions 表的 `status`/`archived_at` 列在 18 节已建好，本节只是第一次真正写它们。全部新类型都是 Java 侧视图/契约对象。

## core 新增（用户批准的 SessionManager 纯加法扩展，包 `com.oryxos.core.session`）

### SessionSummary（新 record，只读列表视图）
| 字段 | 类型 | 来源列 |
|---|---|---|
| sessionId | String | sessions.session_id |
| profileName | String | sessions.profile_name |
| channel | String | sessions.channel |
| userId | String | sessions.user_id |
| status | String | sessions.status（active/archived） |
| createdAt | String | sessions.created_at |
| lastActiveAt | String | sessions.last_active_at |
| archivedAt | String | sessions.archived_at（可空） |

### SessionPage（新 record，分页信封）
`page`（int）、`size`（int）、`total`（long）、`content`（List<SessionSummary>）。

### Session（既有类加法）
新增只读标记 `archived()`（boolean），由 `JpaSessionManager.toRuntime` 按实体 `status` 填充；`getOrCreate` 新建的运行时会话恒为 false。既有构造器与 `id()/profileName()/messages()/append...` 签名不变。

## SessionManager 端口新增方法（既有三方法签名不动）

- `boolean archive(String sessionId)` —— 不存在返回 false；存在且未归档：置 `status="archived"`、`archived_at=now` 返回 true；已归档幂等返回 true（不覆写 archived_at）。
- `SessionPage listSessions(int page, int size)` —— `last_active_at` 倒序；`page<0 || size<1` 抛 IllegalArgumentException（→400）；`size>100` 收敛为 100。

## web 层 REST DTO（`com.oryxos.web.api` 内各 Controller 就近放置的 record）

| DTO | 字段 | 校验 |
|---|---|---|
| CreateSessionRequest | profileName, userId | 均必填非空白，否则 400 |
| SessionSummaryResponse | sessionId, profileName, channel, userId, status, createdAt, lastActiveAt | 创建端点的响应载荷 |
| SessionPageResponse | page, size, total, content: List<SessionSummary> | — |
| MessageRequest | content | 必填；>32KB → 400 |
| MessageResponse | reply | 课件既定字面量 |
| SessionMessageView | role, content, toolCalls(可空) | 历史条目视图（实体整列 JSON 无时间戳，不发明字段） |
| SessionDetailResponse | sessionId, profileName, status, totalMessages, messages(≤100 条) | 截断取最近 100 |
| InvokeRequest | content | 同 MessageRequest 规则 |
| ProfileSummaryResponse | name, description, agentName, provider, model | 来自 Profile/Identity/Provider |
| ToolSummaryResponse | name, description | 来自 OryxTool |
| MemoryResponse | backend, content | content=LongTermMemoryStore.load() 全文 |
| HealthResponse | status（固定 "ok"） | — |
| InfoResponse | name, version, description, providers: List<ProviderStatus> | — |
| ProviderStatus | name, status（registered/unavailable） | 口径见 spec 假设 |

## 状态机

```
（不存在） --POST /sessions--> active --DELETE /sessions/{id}--> archived
active     --POST messages--> active（历史累积）
archived   --POST messages--> 400 拒绝（FR-002，clarify Q2）
```

## 存储映射

- 归档：`SessionEntity` 新增包内/公有变更方法（如 `markArchived(String archivedAt)`），由 `JpaSessionManager.archive` 调用；表结构不变。
- 列表：`SessionRepository`（JpaRepository）用 `findAll(Pageable)` + `count()` 实现分页，不新增派生查询方法。

## 校验规则汇总

- 32KB 上限：`content.length() > 32 * 1024` → 400（课件既定字面量）。
- 历史返回上限：最近 100 条（FR-010）。
- 列表分页：size ≤ 100，非法页参 → 400。
- 会话三元组分量校验沿用 `JpaSessionManager.composeId` 既有规则（非空、禁冒号）。
