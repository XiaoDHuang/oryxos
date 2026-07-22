---
title: Demand Analysis
---

# OryxOS Demand Analysis

This document defines the functional and non-functional requirements of the OryxOS project, serving as the basis for subsequent technical solution design, R&D implementation, and testing acceptance. This document answers What, not How; How is covered in the subsequent technical solution.

## Project Overview

OryxOS is a Java-based Agent OS for enterprise scenarios. It runs on your own Kubernetes or servers as a unified foundation for various business agents (ops assistants, customer service, HR, sales, knowledge management, etc.), sharing channel access, model routing, tool invocation, memory systems, and sandbox execution. Data stays entirely within your infrastructure — no cloud ecosystem lock-in.

A critical layered judgment runs throughout this document:

> **Agent OS is not the same as an agent runtime.**
>
> An agent runtime is the execution kernel that makes a single agent run — LLM calls, tool execution, context management, loop control.
>
> An Agent OS contains a runtime at its core, but above it manages agent lifecycle, unified inbound/outbound access, unified memory, multi-tenancy, and audit governance.

OryxOS is delivered in two phases:

- **Core phase**: Build the Agent OS runtime kernel in Java, aligned with the foundational layer of existing open-source Agent OS projects.
- **Enterprise governance layer**: The true differentiated endgame (multi-tenancy, SSO, full audit, Tool governance), gradually delivered through the extension phase and community contributions.

In other words, the core phase delivers the kernel foundation of the Agent OS, not a fully governed enterprise-grade Agent OS.

## Core Features

Core features are the minimum viable path that must be completed in the 4-week core phase (12 hours total), corresponding to the Agent OS runtime kernel. The goal is to complete a full chain: configure an agent with a Profile, chat with it via CLI, have it call LLMs and tools to complete tasks, and expose capabilities through a REST API.

### Five Core Capabilities

1. **LLM Providers**: Provider abstraction; agents are agnostic to the underlying model
2. **ReAct Loop**: The core mechanism, Reason + Act
3. **Three-Layer Memory**: Session memory + long-term memory (`MEMORY.md`); episodic memory in extension phase
4. **Plugin Tool + Built-in Tools**: File, Shell, HTTP built-in tools; Plugin Tools with three access levels
5. **Web Service**: Complete REST API exposure; business systems integrate via HTTP

### 12 CLI Commands

`init`, `status`, `chat`, `serve`, `gateway`, `profile list/create/show/delete`, `provider list`, `tool list`, `session list`

### Three Run Modes

`oryxos chat` (interactive), `oryxos serve` (Web Service), `oryxos gateway` (multi-channel daemon) share the same Profile configuration and Session storage.

## Extension Features

Extension features advance after core features, completing production-grade capabilities not on the minimum viable path — including the governance layer that makes OryxOS a true enterprise Agent OS.

- **Channel and Model Layer**: Multi-channel access, Provider Fallback, Adaptive Routing
- **Memory and Capability Layer**: Memory auto-extraction, semantic search, episodic memory, Memory Wiki, Skill system
- **Tool and Security Layer**: MCP Server exposure, Tool Policy, Tool LRU loading, full Sandbox
- **Governance and Operations Layer**: Web dashboard, SSO and multi-tenancy, audit and traceability, observability, clustered deployment
- **Enterprise Integration Layer**: ERP / CRM / CMDB / monitoring system connectors

## Non-Functional Requirements

- **Performance**: Single node ≥10 agents, ≥100 concurrent sessions, session creation P99 < 200ms, internal forwarding overhead < 50ms
- **Reliability**: Profiles and Sessions never lost; LLM provider failures return errors in core phase; Tool failures retry with exponential backoff, max 3 times
- **Operability**: Profile changes take effect after restart; supports physical machines / VMs / Docker / Kubernetes deployment
- **Compatibility**: JDK 21+; mainstream Linux distributions; OpenAI-compatible protocol
- **Security**: HTTPS; sensitive configs never in plaintext; Tool invocation whitelist validation
- **Compliance**: OryxOS never actively sends data outside

## Five Acceptance Demos

| Demo | Validates | Content |
| --- | --- | --- |
| Demo 1 | LLM + ReAct | Check weather and write daily report |
| Demo 2 | Memory | Remember preferences across dialogues |
| Demo 3 | Plugin Tool + MCP | Zero-code `SKILL.md` runs daily PR digest |
| Demo 4 | Web Service Sync Call | Create Session → Send Message → Get History → Archive |
| Demo 5 | Web Service Multi-Endpoint | `info` → `profiles` → `tools` → `invoke` → `memory` |

## Full Document

> 📄 The complete demand document is approximately 60K characters, including detailed data models, milestone planning, and acceptance criteria.

**[View full document on GitHub](https://github.com/XiaoDHuang/oryxos/blob/main/docs/DemandAnalysis.md)**
