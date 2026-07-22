---
title: Quick Start
---

# Quick Start

> ⚠️ The Maven multi-module skeleton can compile and package; agent runtime capabilities are still being implemented in the core phase. The `init` command below is already available; the rest represent the target experience.

## 1. Initialize Workspace

```bash
oryxos init
```

`oryxos init` creates a `.oryxos/` workspace in the current directory containing:

- `profiles/` — Profile YAML files
- `sessions/` — Session history
- `skills/` — SKILL.md files
- `logs/` — Structured logs
- `tools/` — Custom tool configurations
- `memory/MEMORY.md` — Long-term memory
- `AGENTS.md` / `SOUL.md` / `USER.md` — Bootstrap files
- `mcp_servers.yaml` — MCP configuration
- `oryxos.db` — SQLite database
- `profiles/default.yaml` — Default profile

## 2. Configure Provider

Edit the default profile and fill in your LLM API key:

```bash
vim .oryxos/profiles/default.yaml
```

Sensitive values use `${ENV_VAR}` placeholders, loaded from environment variables:

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

## 3. Start Chatting

```bash
oryxos chat
```

Or start in service mode:

```bash
oryxos serve --port 8080
```

## 4. Common Commands

```bash
# Check status
oryxos status

# Profile management
oryxos profile list
oryxos profile create ops-assistant
oryxos profile show default

# Queries
oryxos provider list
oryxos tool list
oryxos session list
```

## 5. Call via REST API

```bash
# Create session
curl -X POST http://localhost:8080/api/v1/sessions \
  -H "Content-Type: application/json" \
  -d '{"profile":"default","channel":"web","user_id":"user-001"}'

# Send message
curl -X POST http://localhost:8080/api/v1/sessions/{session_id}/messages \
  -H "Content-Type: application/json" \
  -d '{"content":"Check Beijing weather and tell me what to wear"}'

# Get history
curl http://localhost:8080/api/v1/sessions/{session_id}

# Archive
curl -X DELETE http://localhost:8080/api/v1/sessions/{session_id}
```

## Build and Run

```bash
# Build fat JAR
mvn clean package

# Run
java -jar oryxos-boot/target/oryxos-boot-*.jar init
java -jar oryxos-boot/target/oryxos-boot-*.jar chat --profile default
java -jar oryxos-boot/target/oryxos-boot-*.jar serve --port 8080
```
