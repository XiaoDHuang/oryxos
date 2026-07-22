---
title: What is OryxOS
---

# What is OryxOS

**OryxOS** is a Java-based Agent OS designed for enterprise scenarios.

It runs on your own Kubernetes clusters, servers, or physical machines as a unified foundation for multiple business agents (ops assistants, customer service agents, HR agents, sales agents, knowledge management assistants, etc.), sharing channel access, model routing, tool invocation, memory systems, and sandbox execution. **Data stays entirely within your own infrastructure — no cloud ecosystem lock-in.**

Open-source projects have already validated this design (OpenClaw in Node.js, Hermes Agent in Python), but no project in the Java ecosystem has ever positioned itself as an "Agent OS." Java is the de facto standard for enterprise backends, and Spring AI Alibaba has already solved low-level LLM integration. What is missing is the layer above — the "Agent OS." **OryxOS fills this gap.**

> **Agent OS is not the same as an agent runtime.**
> An agent runtime is the execution kernel that makes a single agent run (LLM calls, tool execution, context management, loop control). An Agent OS sits above the runtime and manages the lifecycle of multiple agents, unified inbound/outbound access, unified memory, multi-tenancy, and audit governance.
> In short: **a runtime makes one agent run; an Agent OS makes a fleet of agents manageable.**

OryxOS is delivered in two phases:

- **Core phase** (current): Build the **runtime kernel** of the Agent OS in Java, aligned with the foundational layer of existing open-source Agent OS projects.
- **Enterprise governance layer** (multi-tenancy, SSO, full audit, Tool Policy, Web dashboard): gradually delivered through the extension phase and open-source community contributions.

The core phase is the foundation; enterprise governance is the endgame.

## Why OryxOS

What enterprises truly need is not the category of "Agent OS," but **a private, controllable, auditable unified agent foundation**. Especially for strictly regulated industries — banking, government, telecom, energy, healthcare — there are unchangeable requirements:

- Core business data must not leave the enterprise
- Systems must be fully auditable
- Any new component must pass existing security and compliance processes
- Technology stack must align with existing infrastructure

These rules exclude SaaS agent products and public-cloud-locked solutions. It is also difficult to put open-source projects with CVE history and permissive defaults into production. OpenClaw and Hermes are positioned for individuals and small teams, leaving enterprise governance (multi-tenant RBAC, SSO, full audit architecture) unfilled. Neither is Java-based, requiring fragile cross-language glue code to integrate with existing Java backends and operations toolchains (Nacos, Sentinel, SkyWalking, Arthas).

OryxOS anchors itself to this unchanging demand: **private deployment, full auditability, Java-native alignment, data stays inside, and IT control.**

Four words summarize the design goals:

| Goal | Meaning |
|---|---|
| **Unified** | Multiple business agents share one foundation; Channel, Provider, Tool, Memory, and Sandbox are unified in OryxOS |
| **Private** | Data stays entirely on your infrastructure; models can use external APIs or local Ollama / vLLM |
| **Easy Integration** | Standard Spring Boot structure; directly integrates with existing ERP/CRM/CMDB/SSO/monitoring systems |
| **Observable** | Standard Prometheus metrics, structured JSON logs, health checks, fitting existing monitoring and alerting |

## Architecture Overview

![OryxOS Architecture](/architecture.svg)

The project is a **Maven multi-module build (9 modules)**: `oryxos-core` (engine), `oryxos-provider` (capability 1), `oryxos-memory` (capability 3), `oryxos-tool` (capability 4), `oryxos-web` (capability 5), `oryxos-channel-cli`, `oryxos-storage`, `oryxos-cli`, `oryxos-boot`.

Key technical decisions:

- **Self-implemented ReAct loop** — not using Spring AI's Agent abstraction; the core loop is ~50 lines of Java, fully controllable
- **Use Spring AI only half** — only its Provider protocol conversion and `@Tool` schema generation; automatic tool execution is disabled, scheduling is fully controlled by OryxOS's own `ToolExecutor`
- **Synchronous blocking + Java 21 virtual threads** — intuitive code that handles high concurrency
- **Sandbox uses Path/Pattern whitelisting** — not the deprecated `SecurityManager`
- **SQLite + `MEMORY.md` for persistence** — audit tables (`tool_invocations`, `llm_calls`) are persisted from day one
