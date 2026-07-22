---
title: 业界调研
---

# Agent OS 行业调研

本文档聚焦 **Agent OS** 这件事本身，先把 Agent OS 是什么讲清楚，再看业界两个最具代表性的开源 Agent OS 项目（**OpenClaw** 和 **Hermes Agent**）分别做到了什么、企业用得怎么样、留下了什么空白，然后看 Java 生态在这件事上的位置，最后落到 **OryxOS** 的定位和愿景。

OryxOS 想做的事是清晰的：一个企业能完全掌控的、Java 原生的、私有可审计的 Agent 统一底座。调研要回答的是，业界已经做了什么，这件事在 Java 生态里为什么还没人做，以及 OryxOS 想把它做成什么样子。

## 什么是 Agent OS

**Agent OS** 是运行和管理 AI Agent 的底座系统。它装在用户（或企业）自己的机器上，向上为各类 Agent（运维助手、客服助手、HR 助手、销售助手等）提供统一的运行环境，向下接入模型、渠道、工具、记忆、身份和审计基础设施。

一个合格的 Agent OS 必须具备五件事：

1. **Agent 配置和生命周期管理。** 能注册、启动、监控、销毁多个 Agent，每个 Agent 有独立的 prompt、模型、工具、渠道、记忆。Agent 在底座上配置出来，不是写代码写出来的。
2. **统一对外渠道接入。** IM、邮件、Web、HTTP API，所有 Agent 共用一套渠道层。
3. **统一对内系统接入。** LLM Provider、工具、企业 IT 系统、知识库，所有 Agent 共享一套接入层。
4. **统一记忆。** 跨 Session 的长期记忆、可复用的 Skill 模板、跨 Agent 的知识沉淀。
5. **Tool 调用和沙箱执行。** Agent 通过 LLM Function Calling 调用 Tool，Tool 在沙箱里执行，保证安全边界。

这里要把一个容易混的词辨清楚：**Agent OS** 跟 **agent runtime** 不是一回事。

**agent runtime** 指的是让单个 agent 跑起来的执行内核，负责 LLM 调用、工具执行、上下文管理、循环控制。Agent OS 的内核确实包含一个 agent runtime，但它在 runtime 之上还要管前四件事：多个 Agent 的生命周期、统一的对外对内接入、统一记忆、以及多租户和审计。

> 一句话：Agent OS 解决的是「用户要同时跑 N 个 Agent 时，这些 Agent 共享的基础设施层应该长什么样」。

## 业界两个最具代表性的开源 Agent OS

**OpenClaw** 由 PSPDFKit 创始人 Peter Steinberger 在 2025 年 11 月发布，Node.js 实现，MIT 协议。它代表的是消费者级、开发者优先的取向：二十多个渠道、上万个社区 skill、极强的可玩性。它的强项是社区活力和能力丰富度，软肋是企业级安全和治理。一句话，OpenClaw 是个人和小团队的 Agent OS。

**Hermes Agent** 由开源 AI 实验室 NousResearch 在 2026 年 2 月发布，Python 实现，MIT 协议。它代表的是工程级、健壮性优先的取向：三层记忆、自我进化的 skill 机制、安全扫描、`HERMES_HOME` 多用户隔离，企业级方向投入明显。它比 OpenClaw 更接近企业，但企业级 OS 治理（多租户 RBAC、SSO、完整审计）仍是空白。一句话，Hermes 是更偏团队和企业的 Agent OS。

两个项目合起来，基本就勾勒出了当前开源 Agent OS 的格局：一个偏消费级可玩、一个偏工程级健壮，都从个人和小团队起步。这个格局的意义不在于它们各自做了什么，而在于它们合起来留下了什么空白，而那个空白正是理解 OryxOS 定位的起点。

## 企业为什么需要一个私有可控的 Agent 底座

企业真正的刚需，不是「Agent OS 这个品类」，而是「一个私有、可控、可审计的 Agent 统一底座」。这两者听起来像，但锚点完全不同。前者是一个可能演变的概念，后者是一个不会变的需求。

把镜头对准最硬的那批客户，也就是银行、政府、电信、能源、医疗。这些严监管企业有几条铁律：

- 核心业务的数据不能出企业
- 系统必须完全可审计
- 任何新组件都要过现有的安全和合规流程
- 技术栈要跟现有体系对齐

在这几条铁律下，他们的选择被极大地收窄了。他们需要的是一个私有部署、完全可审计、能纳入现有 IT 治理、跟现有技术栈对齐的 Agent 底座。

这个需求是确定的、刚性的、且当前无人满足的。

## Java 生态在这件事上的位置

Java 在 AI 工程领域有项目，但没有任何一个是 Agent OS 这一层的底座。

**Spring AI** 是 Java AI 应用开发框架，**Spring AI Alibaba** 是阿里推动的 Spring AI 扩展，**LangChain4j** 是 LangChain 的 Java 移植。这些都是库或框架，产物是代码、需要开发者自己搞定运行环境。Java 写的、装好就跑的 Agent OS 底座，在业界几乎为零。

一个健康的技术生态，应该在每一个关键层级都有自己的实现。否则这个生态在那一层就有一道断裂，断裂处就要靠跨生态的胶水去填，而胶水是脆的、是成本。

Java/Spring 生态在企业后端是极其完整的，从 Web 框架（Spring Boot）、微服务（Spring Cloud）、配置注册（Nacos）、限流熔断（Sentinel）、链路追踪（SkyWalking）、线上诊断（Arthas）到监控告警（Prometheus + Grafana），每一层都有成熟的、互相咬合的实现。企业的 ERP、CRM、CMDB、SSO、监控，大量是 Java 接口或 Java SDK。

唯独在「Agent OS」这一层，Java 生态是空的。

## OryxOS 的定位与愿景

**OryxOS** 是一个企业能完全掌控的、Java 原生的、私有可审计的 Agent 统一底座。

它装在企业自己的 K8s、服务器或物理机上，作为统一底座，在底座上跑各种业务 Agent，共享一套渠道接入、模型路由、记忆系统、工具调用、安全审计能力。数据完全留在企业自己的基础设施，不锁任何云生态。

OryxOS 用「Agent OS」这个框架来理解和构建自己，但 OryxOS 不把自己锚在「Agent OS 这个概念」上，而是锚在它背后那个不会变的企业刚需上：严监管企业需要一个自己能完全掌控的 Agent 底座。

愿景可以用四个词概括：

- **统一** — 企业内多个 Agent 共享一套底座
- **私有** — 数据和部署完全在企业自己手里
- **易接入** — 基于标准 Spring Boot 工程结构、跟现有系统和工具链直接对接、Tool 用 MCP 任何语言都能写
- **可观测** — 标准 Prometheus 指标、结构化日志、健康检查、Web 管理台，适配企业现有监控告警

## 完整文档

> 📄 完整调研文档约 4 万字，包含详细的数据引用、案例分析和未来分布式演进方向。

**[在 GitHub 上查看完整文档](https://github.com/XiaoDHuang/oryxos/blob/main/docs/IndustryResearch.md)**
