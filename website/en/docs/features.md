---
title: Core Features
---

# Core Features

The core phase focuses on five capabilities, all belonging to the runtime kernel layer that makes a single agent run well. These five capabilities can be combined to address numerous real enterprise needs. OryxOS itself is not bound to any specific business.

| # | Capability | One-liner |
|---|---|---|
| 1 | **LLM Providers** | Abstracted access to DeepSeek, Qwen, Kimi, Zhipu, Hunyuan, Doubao, Anthropic, OpenAI, etc. Agents are agnostic to the underlying model; runtime switching has no lock-in |
| 2 | **ReAct Loop** | The core mechanism: LLM decides whether to call a tool, sees the result, then decides the next step until a final response |
| 3 | **Three-Layer Memory** | Session memory (current dialogue) + long-term memory (`MEMORY.md`, persists user preferences and project context) + episodic memory (extension phase) |
| 4 | **Tool System** | Built-in file/shell/HTTP tools; Plugin Tools with three access levels: zero-code `SKILL.md` + MCP, light-code custom MCP server, heavy-code `@Tool` annotated Spring Bean |
| 5 | **Web Service** | Complete REST API exposure; business systems integrate via HTTP, the only channel for embedding AI capabilities into existing systems |

These five capabilities combine into scenarios like omnichannel customer service, ops assistants, dev assistants, knowledge management, sales assistants, and data analysis — no separate modules required. Business teams configure Profiles, write Plugin Tools, and call the Web Service to land these scenarios.

## Capability 1: LLM Providers

OryxOS accesses mainstream LLMs through a Provider abstraction. All LLM calls go through the Provider interface; agents never know which vendor is being called.

- Based on Spring AI Alibaba's `ChatClient`, reusing 10+ mainstream LLM connectors
- Explicitly maintains a provider name → `ChatModel` mapping table; no ambiguity with multiple providers
- Core phase does not implement fallback or hedge racing; provider failures return errors directly
- Every LLM call records token usage, provider, and model into the `llm_calls` audit table

## Capability 2: ReAct Loop

ReAct is the core mechanism. The LLM decides whether to call a tool, sees the result, then decides the next step until a final response is given.

- Self-implemented ReAct loop; not using Spring AI's Agent abstraction
- Default max 10 iterations, overridable in Profile
- Messages accumulate in Session, fully recording the LLM call chain and Tool call chain
- Tool failures retry with exponential backoff, max 3 times

## Capability 3: Three-Layer Memory

The ability for agents to retain state across dialogues.

- **Session Memory**: Full current dialogue history, SQLite persistence, resumable after restart
- **Long-term Memory**: `.oryxos/memory/MEMORY.md` file; agents read/write via two built-in tools `save_memory` / `recall_memory`
- **Episodic Memory**: Files modified, decisions made, outcomes achieved during tasks — delivered in extension phase

## Capability 4: Tool System

Tools are divided into two categories — the key mechanism for business extensibility.

**Built-in Tools**: `read_file` / `write_file` / `list_dir` / `shell` / `http_get` / `http_post` / `save_memory` / `recall_memory`

**Plugin Tool — three access levels**:

1. **Zero-code**: Write `SKILL.md` to describe intent, reuse community MCP servers
2. **Light-code**: Write your own MCP server in any language
3. **Heavy-code**: Write Java Spring Beans with `@Tool` annotations

## Capability 5: Web Service

The external facade of OryxOS; business systems integrate via REST API.

Core phase — 10 key endpoints:

| Endpoint | Description |
|---|---|
| `POST /api/v1/sessions` | Create session |
| `POST /api/v1/sessions/{id}/messages` | Send message |
| `GET /api/v1/sessions/{id}` | Get session history |
| `DELETE /api/v1/sessions/{id}` | Archive session |
| `POST /api/v1/agents/{name}/invoke` | Stateless agent invocation |
| `GET /api/v1/profiles` | List profiles |
| `GET /api/v1/memory` | Query long-term memory |
| `GET /api/v1/tools` | List available tools |
| `GET /api/v1/health` | Health check |
| `GET /api/v1/info` | System info |

For more design details, see [Demand Analysis](/en/docs/demand-analysis) and [Technical Solution](/en/docs/technical-solution).
