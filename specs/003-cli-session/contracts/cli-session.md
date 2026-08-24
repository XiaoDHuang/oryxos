# Contract: CLI 命令与会话层

## SessionManager(core,扩展后完整契约)

```java
Session getOrCreate(String channel, String user, String profileName);
```

- 幂等:同一合法三元组永远返回同一 sessionId 的 Session;id 形如 `cli:wang:default`,拼接只在实现内部。channel/user/profile 任一分量为空白或含冒号时必须在落库前拒绝。
- 库中已存在 → 回读重建(消息完整);不存在 → 新建 active 会话并立即落库。

```java
Optional<Session> get(String sessionId);
void save(Session session);   // 17 节已有契约,语义不变
```

## 12 个子命令(oryxos-cli,Picocli @Command)

| 命令 | 轻重 | 行为 |
|---|---|---|
| `init` | 轻(既有) | 创建 `.oryxos/` 工作区 |
| `status` | 轻 | 工作区是否初始化、Profile 数、会话数 |
| `profile list` / `show` / `create` / `delete` | 轻 | profiles 目录文件的列/看/建模板/删 |
| `provider list` | 轻 | 扫 profiles YAML 汇总引用的 provider 名 |
| `tool list` | 轻 | 列指定 profile(`--profile`,默认 default)声明的 tools |
| `session list` | 轻 | JDBC 直查 sessions 表(id/profile/channel/status/last_active_at) |
| `chat [--profile]` | 重 | 起 Spring,委托 CliChannel 交互,/quit 退出 |
| `serve [--port]` | 重 | 起 Web 运行时(REST 端点 26 节) |
| `gateway` | 重 | 起非 Web 守护骨架(核心阶段仅 CLI 通道,26 节后补) |

约定:重命令启动类(`OryxOsApplication`)显式声明 `@EnableJpaRepositories("com.oryxos.storage")` + `@EntityScan("com.oryxos.storage")`;未初始化的工作区执行命令报「请先 oryxos init」式清晰错误,不抛栈。
