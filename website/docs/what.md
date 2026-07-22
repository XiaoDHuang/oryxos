---
title: OryxOS 是什么
---

# OryxOS 是什么

**OryxOS** 是基于 Java 实现的、面向企业场景的 **Agent OS**。

它装在企业自己的 K8s、服务器或物理机上，作为统一底座，在底座之上跑各种业务 Agent（运维助手、客服助手、HR 助手、销售助手、知识管理助手等），共享一套渠道接入、模型路由、工具调用、记忆系统、沙箱执行能力。**数据完全留在企业自己的基础设施上，不锁定任何云生态。**

业界已经有开源项目验证过这套设计（OpenClaw 用 Node.js、Hermes Agent 用 Python），但 Java 生态里从来没有项目把"Agent OS"作为自己的定位。Java 是大量企业现有后端的事实标准技术栈，Spring AI Alibaba 已经把底层 LLM 调用解决了，缺的就是上面那一层"Agent OS"。**OryxOS 填这个位置。**

> **Agent OS 跟 agent runtime 不是一回事。**
> agent runtime 是让单个 Agent 跑起来的执行内核（LLM 调用、工具执行、上下文管理、循环控制）。Agent OS 在 runtime 之上，还要管一群 Agent 的生命周期、统一的对外对内接入、统一记忆、多租户、审计这些 OS 级治理能力。
> 一句话：**runtime 让一个 Agent 跑起来，Agent OS 让一群 Agent 在企业里被管起来。**

OryxOS 的交付分两段：

- **核心阶段**（当前）：把 Agent OS 的**运行时内核**用 Java 做扎实，能力上对齐业界开源 Agent OS 的基础层。
- **企业级治理层**（多租户、SSO、完整审计、Tool Policy、Web 仪表板）：由扩展阶段和开源社区共建陆续补齐，是 OryxOS 真正的差异化终局。

核心阶段是地基，企业级治理是终局。

## 为什么是 OryxOS

企业真正的刚需不是"Agent OS 这个品类"，而是**一个私有、可控、可审计的 Agent 统一底座**。尤其是银行、政府、电信、能源、医疗这类严监管企业，有几条不会变的铁律：

- 核心业务数据不能出企业
- 系统必须完全可审计
- 任何新组件都要过现有的安全和合规流程
- 技术栈要跟现有体系对齐

这几条铁律直接排除了 SaaS 化的 Agent 产品、锁定某个公有云生态的方案，也很难把一个有 CVE 历史、默认权限宽松的开源项目直接放进生产。而 OpenClaw、Hermes 都偏个人到小团队定位，企业级治理（多租户 RBAC、SSO、完整审计架构）仍是空白；两者也都不是 Java，跟企业现有的 Java 后端、运维工具链（Nacos、Sentinel、SkyWalking、Arthas）之间总要写一层脆弱的跨语言胶水代码。

OryxOS 把自己锚在这个不会变的需求上：**私有部署、完全可审计、Java 原生对齐、数据不出企业、IT 能掌控。**

四个词概括设计目标：

| 目标 | 含义 |
|---|---|
| **统一** | 企业内多个业务 Agent 共享同一套底座，Channel、Provider、Tool、Memory、Sandbox 下沉到 OryxOS |
| **私有** | 数据完全留在企业自己的基础设施，模型可接外部 API 也可用本地 Ollama / vLLM |
| **易接入** | 标准 Spring Boot 工程结构，跟企业现有 ERP/CRM/CMDB/SSO/监控系统直接对接 |
| **可观测** | 标准 Prometheus 指标、结构化 JSON 日志、健康检查接口，适配企业现有监控告警体系 |

## 架构总览

![OryxOS 架构](/architecture.svg)

工程上是 **Maven 多模块（9 个模块）**：`oryxos-core`（引擎）、`oryxos-provider`（能力一）、`oryxos-memory`（能力三）、`oryxos-tool`（能力四）、`oryxos-web`（能力五）、`oryxos-channel-cli`、`oryxos-storage`、`oryxos-cli`、`oryxos-boot`。

关键技术决策：

- **自己实现 ReAct loop**，不依赖 Spring AI 的 Agent 抽象，核心循环约数十行 Java，完整可控
- **Spring AI 只用一半**：只用它的 Provider 协议转换和 `@Tool` 的 Schema 生成，禁用其自动 tool 执行，调度完全由 OryxOS 自己的 `ToolExecutor` 控制
- **同步阻塞 + Java 21 virtual thread**，代码直观又能扛住高并发
- **Sandbox 用 Path/Pattern 白名单**，不用已在 JDK 21 废弃的 `SecurityManager`
- **SQLite + `MEMORY.md` 文件**做持久化，审计表（`tool_invocations`、`llm_calls`）核心阶段就落库
