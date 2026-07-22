---
title: 需求分析
---

# OryxOS 需求分析

本文档定义 OryxOS 项目的功能需求和非功能需求，作为后续技术方案设计、研发实施、测试验收的依据。本文档回答 What，不回答 How，How 在后续的技术方案中展开。

## 项目概述

OryxOS 是基于 Java 实现的面向企业场景的 Agent OS。它装在企业自己的 K8s 或服务器上，作为统一底座，在底座上跑各种业务 Agent（运维助手、客服助手、HR 助手、销售助手、知识管理助手等），共享一套渠道接入、模型路由、工具调用、记忆系统、沙箱执行能力。数据完全留在企业自己的基础设施，不锁任何云生态。

这里要先说清楚一个贯穿全文的分层判断，它决定了怎么理解后面的功能规划。

> **Agent OS 跟 agent runtime（Agent 运行时）不是一回事。**
>
> agent runtime 是让单个 Agent 跑起来的执行内核，负责 LLM 调用、工具执行、上下文管理、循环控制。
>
> Agent OS 的内核包含一个 agent runtime，但它在 runtime 之上还要管多个 Agent 的生命周期、统一的对外对内接入、统一记忆、多租户、审计这些 OS 级治理能力。

OryxOS 的交付分两段：

- **核心阶段**先把 Agent OS 的运行时内核用 Java 做扎实，这一层在能力上对齐业界开源 Agent OS 的基础层；
- **OryxOS 真正的差异化治理层**，在核心内核之上、由扩展阶段和社区共建陆续补齐。

换句话说，核心阶段交付的是 Agent OS 的内核底座，而不是一个治理能力完备的企业级 Agent OS，后者是终局，核心阶段是地基。

## 核心功能

核心功能是核心阶段 4 周（合计 12 小时）内必须完成的最短链路，对应 Agent OS 的运行时内核。目标是跑通一个完整链路：用 Profile 配置一个 Agent，通过 CLI 跟它对话，它能调用 LLM 和工具完成任务，并能通过 REST API 对外暴露。

### 五大核心能力

1. **对接 LLM**：Provider 抽象层，Agent 不感知具体调的是哪家模型
2. **ReAct 循环**：Agent 的核心工作机制，Reason + Act
3. **Memory 三层记忆**：会话记忆 + 长期记忆（`MEMORY.md`），情景记忆放扩展
4. **Plugin Tool + 内置工具**：文件、Shell、HTTP 内置工具，Plugin Tool 三档接入
5. **Web Service**：完整 REST API 对外暴露，业务系统通过 HTTP 接入

### 12 个命令行工具

`init`、`status`、`chat`、`serve`、`gateway`、`profile list/create/show/delete`、`provider list`、`tool list`、`session list`

### 三种运行模式

`oryxos chat`（交互对话）、`oryxos serve`（Web Service）、`oryxos gateway`（多渠道守护进程）共享同一份 Profile 配置和 Session 存储。

## 扩展功能

扩展功能在核心功能完成后推进，补齐生产级使用必需但不在最短链路上的能力，其中包含让 OryxOS 成为真正企业级 Agent OS 的治理层。

- **渠道和模型层**：多 Channel 接入、Provider Fallback、Adaptive Routing
- **记忆和能力层**：Memory 自动抽取、语义检索、情景记忆、Memory Wiki、Skill 体系
- **工具和安全层**：MCP Server 暴露、Tool Policy、Tool LRU 加载、完整 Sandbox
- **治理和运维层**：Web 仪表板、SSO 和多租户、审计与可追溯、可观测性、集群化部署
- **企业集成层**：ERP / CRM / CMDB / 监控系统 connector

## 非功能需求

- **性能**：单节点 ≥10 个 Agent、≥100 并发 Session、Session 创建 P99 < 200ms、内部转发开销 < 50ms
- **可靠性**：Profile 和 Session 不丢；LLM Provider 故障核心阶段直接报错；Tool 失败指数退避最多 3 次
- **可运维性**：Profile 修改后重启生效；支持物理机/虚拟机/Docker/K8s 部署
- **兼容性**：JDK 21+；Linux 主流发行版；OpenAI 兼容协议
- **安全**：HTTPS；敏感配置不明文；Tool 调用白名单校验
- **合规**：OryxOS 不主动外发任何数据

## 五个验收 Demo

| Demo | 验证能力 | 内容 |
| --- | --- | --- |
| Demo 一 | 对接 LLM + ReAct | 查天气并写日报 |
| Demo 二 | Memory | 跨对话记住偏好 |
| Demo 三 | Plugin Tool + MCP | 零代码 `SKILL.md` 跑通 daily PR digest |
| Demo 四 | Web Service 同步调用 | 创建 Session → 发消息 → 查历史 → 归档 |
| Demo 五 | Web Service 多端点联动 | `info` → `profiles` → `tools` → `invoke` → `memory` |

## 完整文档

> 📄 完整需求文档约 6 万字，包含详细的数据模型、里程碑规划和验收标准。

**[在 GitHub 上查看完整文档](https://github.com/XiaoDHuang/oryxos/blob/main/docs/DemandAnalysis.md)**
