---
title: Industry Research
---

# Agent OS Industry Research

This research focuses on **Agent OS** itself. It first clarifies what an Agent OS is, then examines two of the most representative open-source Agent OS projects in the industry (**OpenClaw** and **Hermes Agent**), their achievements, enterprise adoption, and the gaps they leave. Finally, it looks at the position of the Java ecosystem and lands on **OryxOS**'s positioning and vision.

OryxOS's goal is clear: an enterprise-controlled, Java-native, private, auditable unified agent foundation. The research answers what the industry has already done, why no one in the Java ecosystem has done this, and what OryxOS aims to become.

## What is an Agent OS

**Agent OS** is the foundational system that runs and manages AI agents. It is installed on the user's (or enterprise's) own machines, providing a unified runtime environment for various agents (ops assistants, customer service, HR, sales, etc.) upward, and connecting models, channels, tools, memory, identity, and audit infrastructure downward.

A qualified Agent OS must have five things:

1. **Agent configuration and lifecycle management.** Register, start, monitor, and destroy multiple agents. Each agent has independent prompts, models, tools, channels, and memory. Agents are configured on the foundation, not coded.
2. **Unified outbound channel access.** IM, email, Web, HTTP API — all agents share one channel layer.
3. **Unified inbound system access.** LLM providers, tools, enterprise IT systems, knowledge bases — all agents share one access layer.
4. **Unified memory.** Cross-session long-term memory, reusable Skill templates, cross-agent knowledge accumulation.
5. **Tool invocation and sandbox execution.** Agents call tools via LLM Function Calling; tools execute in sandboxes with security boundaries.

An important distinction: **Agent OS is not the same as an agent runtime.**

An **agent runtime** is the execution kernel that makes a single agent run — LLM calls, tool execution, context management, loop control. An Agent OS contains a runtime at its core, but above it manages agent lifecycle, unified access, unified memory, multi-tenancy, and audit.

> In short: Agent OS answers "when users need to run N agents simultaneously, what should the shared infrastructure layer look like?"

## The Two Most Representative Open-Source Agent OS Projects

**OpenClaw** was released by PSPDFKit founder Peter Steinberger in November 2025, implemented in Node.js under MIT license. It represents a consumer-grade, developer-first orientation: 20+ channels, 10K+ community skills, strong playability. Its strength is community vitality and capability richness; its weakness is enterprise security and governance. In short, OpenClaw is an Agent OS for individuals and small teams.

**Hermes Agent** was released by open-source AI lab NousResearch in February 2026, implemented in Python under MIT license. It represents an engineering-grade, robustness-first orientation: three-layer memory, self-evolving skill mechanisms, security scanning, `HERMES_HOME` multi-user isolation. It is closer to enterprise than OpenClaw, but enterprise OS governance (multi-tenant RBAC, SSO, full audit) remains unfilled. In short, Hermes is an Agent OS leaning toward teams and enterprises.

Together, these two projects outline the current open-source Agent OS landscape: one consumer-grade and playful, one engineering-grade and robust, both starting from individuals and small teams. The significance of this landscape is not what each has done, but the gaps they collectively leave — and that gap is the starting point for understanding OryxOS's positioning.

## Why Enterprises Need a Private, Controllable Agent Foundation

What enterprises truly need is not the category of "Agent OS," but "a private, controllable, auditable unified agent foundation." These sound similar but have completely different anchors. The former is a concept that may evolve; the latter is an unchanging need.

Looking at the most demanding customers — banking, government, telecom, energy, healthcare — these strictly regulated enterprises have several iron rules:

- Core business data must not leave the enterprise
- Systems must be fully auditable
- Any new component must pass existing security and compliance processes
- Technology stack must align with existing infrastructure

Under these rules, their choices are extremely narrowed. They need a privately deployed, fully auditable, Java-native agent foundation that integrates with existing IT governance and aligns with their technology stack.

This need is definite, rigid, and currently unmet.

## The Java Ecosystem's Position

Java has AI engineering projects, but none is an Agent OS foundation.

**Spring AI** is Spring's official Java AI application development framework. **Spring AI Alibaba** is Alibaba's extension, providing connectors for 10+ mainstream LLMs. **LangChain4j** is LangChain's Java port. These are libraries or frameworks — their output is code, and developers must handle their own runtime environments. A Java-based, install-and-run Agent OS foundation is essentially zero in the industry.

A healthy technology ecosystem should have its own implementation at every key layer. Otherwise, there is a fracture at that layer, filled by fragile cross-ecosystem glue code.

The Java/Spring ecosystem is extremely complete in enterprise backends — from web frameworks (Spring Boot), microservices (Spring Cloud), configuration registries (Nacos), rate limiting and circuit breaking (Sentinel), distributed tracing (SkyWalking), online diagnostics (Arthas), to monitoring and alerting (Prometheus + Grafana). Enterprise ERP, CRM, CMDB, SSO, and monitoring systems are largely Java interfaces or SDKs.

Only at the "Agent OS" layer is the Java ecosystem empty.

## OryxOS Positioning and Vision

**OryxOS** is an enterprise-controlled, Java-native, private, auditable unified agent foundation.

It installs on your own Kubernetes clusters, servers, or physical machines as a unified foundation for various business agents, sharing channel access, model routing, memory systems, tool invocation, and security audit capabilities. Data stays entirely within your infrastructure — no cloud ecosystem lock-in.

OryxOS uses the "Agent OS" framework to understand and build itself, but it does not anchor itself to the concept. Instead, it anchors to the unchanging enterprise need behind it: strictly regulated enterprises need an agent foundation they can fully control.

The vision in four words:

- **Unified** — Multiple agents share one foundation
- **Private** — Data and deployment entirely in enterprise hands
- **Easy Integration** — Standard Spring Boot structure, direct integration with existing systems and toolchains, MCP-based tools in any language
- **Observable** — Standard Prometheus metrics, structured logs, health checks, Web dashboard, fitting existing monitoring

## Full Document

> 📄 The complete research document is approximately 40K characters, including detailed data citations, case analyses, and future distributed evolution directions.

**[View full document on GitHub](https://github.com/XiaoDHuang/oryxos/blob/main/docs/IndustryResearch.md)**
