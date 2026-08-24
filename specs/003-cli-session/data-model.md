# Data Model: CLI 与会话持久化

## SessionEntity(oryxos-storage,`com.oryxos.storage.session.SessionEntity`,JPA)

对应 `sessions` 表(脚本已有,不动结构):

| 列 | 类型 | 说明 |
|---|---|---|
| `session_id` | TEXT PK | `channel:user:profile` 拼接,只在 JpaSessionManager 内生成 |
| `profile_name` | TEXT NOT NULL | 关联 Profile |
| `channel` | TEXT | cli / web / scheduler |
| `user_id` | TEXT | 用户标识 |
| `messages_json` | TEXT | 消息历史 JSON:`[{role, content, toolCalls?[{id,type,name,arguments}]}]` |
| `context_state` | TEXT | 既有列保留(核心阶段不填) |
| `status` | TEXT NOT NULL | `active`/`archived` |
| `created_at` / `last_active_at` / `archived_at` | TEXT | ISO-8601 |

## 运行时 Session(core,17 节,本节不动)

`id`/`profileName`/`List<Message> messages`;`JpaSessionManager` 负责 Session ↔ SessionEntity 双向转换(消息列表 ↔ messages_json)。

## SessionManager(core 接口,本节扩展)

```java
Session getOrCreate(String channel, String user, String profileName);  // 幂等
Optional<Session> get(String sessionId);
void save(Session session);   // 17 节已有
```

状态机:创建即 `active`;`archived` 由后续 Web 归档端点写入(26 节),本节只保留字段。
