---
title: 快速开始
---

# 快速开始

> ⚠️ Maven 多模块骨架已可编译打包；Agent 运行时能力仍在核心阶段实现中。下面命令中，`init` 已可用，其余为成形后的目标体验。

## 1. 初始化工作区

```bash
oryxos init
```

`oryxos init` 会在当前目录下创建 `.oryxos/` 工作目录，包含：

- `profiles/` — Profile YAML
- `sessions/` — 会话历史
- `skills/` — SKILL.md
- `logs/` — 结构化日志
- `tools/` — 自定义 Tool 配置
- `memory/MEMORY.md` — 长期记忆
- `AGENTS.md` / `SOUL.md` / `USER.md` — Bootstrap 文件
- `mcp_servers.yaml` — MCP 配置
- `oryxos.db` — SQLite
- `profiles/default.yaml` — 默认 Profile

## 2. 配置 Provider

编辑默认 Profile，填入你的 LLM API Key：

```bash
vim .oryxos/profiles/default.yaml
```

Profile 里的敏感值使用 `${ENV_VAR}` 占位，从环境变量加载：

```yaml
provider:
  name: deepseek
  model: deepseek-chat
  api_key: ${DEEPSEEK_API_KEY}
  temperature: 0.7
```

```bash
export DEEPSEEK_API_KEY=your_api_key
```

## 3. 启动对话

```bash
oryxos chat
```

或者以服务模式启动：

```bash
oryxos serve --port 8080
```

## 4. 常用命令

```bash
# 查看状态
oryxos status

# Profile 管理
oryxos profile list
oryxos profile create ops-assistant
oryxos profile show default

# 查询
oryxos provider list
oryxos tool list
oryxos session list
```

## 5. 通过 REST API 调用

```bash
# 创建会话
curl -X POST http://localhost:8080/api/v1/sessions \
  -H "Content-Type: application/json" \
  -d '{"profile":"default","channel":"web","user_id":"user-001"}'

# 发消息
curl -X POST http://localhost:8080/api/v1/sessions/{session_id}/messages \
  -H "Content-Type: application/json" \
  -d '{"content":"查一下北京天气并告诉我穿什么"}'

# 查历史
curl http://localhost:8080/api/v1/sessions/{session_id}

# 归档
curl -X DELETE http://localhost:8080/api/v1/sessions/{session_id}
```

## 构建与运行

```bash
# 构建 fat JAR
mvn clean package

# 运行
java -jar oryxos-boot/target/oryxos-boot-*.jar init
java -jar oryxos-boot/target/oryxos-boot-*.jar chat --profile default
java -jar oryxos-boot/target/oryxos-boot-*.jar serve --port 8080
```
