---
title: Technical Solution
---

# OryxOS Technical Solution

This document defines the technical solution of OryxOS, answering the How question. It unfolds along the five core capabilities defined in the demand document, providing only responsibilities and functional descriptions for each module without code details.

## Solution Overview

OryxOS is a Spring Boot 3.x monolithic application running on JDK 21, using Spring AI Alibaba for LLM calls, with a self-implemented ReAct loop as the agent core. The entire OryxOS is a single executable JAR, deployable as a single binary.

**Tech stack in one sentence:** JDK 21 + Spring Boot 3.x + Spring AI Alibaba + self-implemented ReAct loop + SQLite + Picocli CLI.

## Seven Key Technical Decisions

| Decision | Choice | Rationale |
|------|------|------------|
| 1. ReAct Loop | Self-implemented, not Spring AI's Agent abstraction | Full control of agent core |
| 2. Spring AI Boundary | Only protocol conversion and schema generation; disable auto tool execution | Prevents double tool invocation |
| 3. Execution Model | Synchronous blocking + virtual threads | Intuitive code with high concurrency |
| 4. Tool Registration | `@Tool` annotation + `OryxTool` abstraction | ReAct loop agnostic to tool source |
| 5. HTTP Layer | Spring MVC + virtual threads | Thousands of concurrent requests on single node |
| 6. Sandbox | Path/Pattern whitelist, no SecurityManager | SecurityManager unavailable in JDK 21 |
| 7. Persistence | SQLite + `MEMORY.md`, audit tables persisted from day one | Auditability foundation from day one |

## Overall Architecture

![OryxOS Architecture](/architecture.svg)

Four layers from top to bottom:

| Layer | Composition | Responsibility |
|------|------|------|
| **Access Layer** | CLI Channel, Web Service REST API | Message in/out |
| **Engine Layer** | `ReActLoop`, `PromptBuilder`, `ToolExecutor` | Agent's brain |
| **Capability Layer** | Provider, Memory, Tool | LLM calls, context, execution for the engine |
| **Foundation Layer** | Profile/Bootstrap/Skill loading, Session storage, SQLite, config and secret loading | Engineering foundation |

**The three capabilities — Provider, Memory, Tool — feed the ReAct loop engine; the engine's capabilities are exposed through CLI and Web Service entrances.**

## Core Capability Modules

### Capability 1: LLM Providers

LLM call complexity is absorbed by Spring AI Alibaba. OryxOS adds a thin wrapper on top, converting Spring AI's `ChatClient` into OryxOS's internal `ProviderService` abstraction.

Key point: **Explicit provider name → ChatModel mapping.** Do not rely on scanning all `ChatModel` beans in the container to distinguish providers; an explicit mapping table is required.

### Capability 2: ReAct Loop

The ReAct loop is OryxOS's most critical code. Input: a user message. Output: the agent's final response. In between: several LLM calls and several tool invocations.

The core loop logic is concise, approximately 50 lines of Java, independent of Spring AI's Agent abstraction.

### Capability 3: Three-Layer Memory

Memory is designed as a unified facade of three layers, exposing only one `MemoryService` interface to the ReAct loop:

- **Session Memory**: SQLite persistence, resumable after restart
- **Long-term Memory**: `MEMORY.md` file, read/write via two built-in tools `save_memory` / `recall_memory`
- **Episodic Memory**: Delivered in extension phase

### Capability 4: Tool System

In the core phase, Tool-related code is merged into a single `oryxos-tool` module (built-in tools, MCP Client, `ToolRegistry`, Sandbox all inside), not split into builtin/skill/mcp modules.

`SKILL.md` is not an executable tool but an instruction template injected into the system prompt, loaded by `ContextLoader`.

### Capability 5: Web Service

Two external entrances: CLI Channel for local interaction and debugging; Web Service for business system integration via REST API.

Core phase — 10 endpoints, all under `/api/v1` prefix.

## Data Persistence

Core phase selects SQLite + Spring Data JPA for relational persistence, and `MEMORY.md` file + keyword search for long-term memory.

**Three core tables:** `sessions`, `tool_invocations`, `llm_calls`

The audit-related `tool_invocations` and `llm_calls` tables are written in the core phase, establishing the auditability data foundation from day one.

## Maven Module Structure

Composed of **9 modules**:

| Module | Corresponds to | Responsibility |
|------|------|------|
| `oryxos-core` | Core engine | `ReActLoop`, `PromptBuilder`, `ToolExecutor`, `ContextLoader`, Session, Profile, `OryxTool` abstractions |
| `oryxos-provider` | Capability 1 | `ProviderService`, Function Calling adaptation, provider name mapping |
| `oryxos-memory` | Capability 3 | `MemoryService` (three-layer unified facade), `LongTermMemory`, `MemoryTools` |
| `oryxos-tool` | Capability 4 | Built-in tools, MCP Client, `ToolRegistry`, `SandboxChecker` (three-in-one) |
| `oryxos-web` | Capability 5 | `WebServer`, six ApiControllers, `GlobalExceptionHandler`, OpenAPI docs |
| `oryxos-channel-cli` | Support | CLI Channel implementation |
| `oryxos-storage` | Support | SQLite storage layer, including `sessions`, `tool_invocations`, `llm_calls` tables |
| `oryxos-cli` | Support | Picocli CLI entry (12 subcommands) |
| `oryxos-boot` | Support | Spring Boot startup module, packages all dependencies into fat JAR |

## Full Document

> 📄 The complete technical solution document is approximately 40K characters, including detailed data models, implementation rhythm, and performance considerations.

**[View full document on GitHub](https://github.com/XiaoDHuang/oryxos/blob/main/docs/TechnicalSolution.md)**
