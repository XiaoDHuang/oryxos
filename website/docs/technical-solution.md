---
title: 技术方案
---

# OryxOS 技术方案

本文档定义 OryxOS 的技术方案，回答 How 的问题。以需求文档定义的五大核心能力为骨架展开，每个模块只给职责和功能说明，不展开代码细节。

## 方案概述

OryxOS 是一个 Spring Boot 3.x 单体应用，跑在 JDK 21 上，基于 Spring AI Alibaba 做 LLM 调用，自己实现 ReAct loop 作为 Agent 核心。整个 OryxOS 是一个可执行 JAR，单二进制部署。

**技术栈一句话总结：** JDK 21 + Spring Boot 3.x + Spring AI Alibaba + 自实现 ReAct loop + SQLite + Picocli 命令行。

## 七个关键技术决策

| 决策 | 选择 | 一句话理由 |
|------|------|------------|
| 一 ReAct 循环 | 自己实现，不用 Spring AI 的 Agent 抽象 | Agent 核心完全可控 |
| 二 Spring AI 边界 | 只用协议转换和 schema 生成，禁用自动 tool 执行 | 否则 tool 被调两次 |
| 三 执行模型 | 同步阻塞加 virtual thread | 代码直观又能扛并发 |
| 四 Tool 注册 | `@Tool` 注解加 `OryxTool` 抽象层 | ReAct 不感知 Tool 来源 |
| 五 HTTP 层 | Spring MVC 加 virtual thread | 单机撑几千并发 |
| 六 Sandbox | Path/Pattern 白名单，不用 SecurityManager | SecurityManager 在 JDK 21 已不可用 |
| 七 持久化 | SQLite 加 `MEMORY.md`，审计表 day one 落库 | 可审计地基从一开始立起来 |

## 整体架构

![OryxOS 架构](/architecture.svg)

从上到下分四层：

| 层级 | 组成 | 职责 |
|------|------|------|
| **接入层** | CLI Channel、Web Service 的 REST API | 消息进出 |
| **引擎层** | `ReActLoop`、`PromptBuilder`、`ToolExecutor` | Agent 的大脑 |
| **能力层** | Provider、Memory、Tool | 给引擎提供 LLM 调用、上下文、执行能力 |
| **基础层** | Profile/Bootstrap/Skill 加载、Session 存储、SQLite、配置与密钥加载 | 工程地基 |

**Provider、Memory、Tool 三个能力供养 ReAct 循环这个引擎，引擎跑出的能力通过 CLI 和 Web Service 两个入口对外提供。**

## 核心能力模块

### 能力一：对接 LLM

LLM 调用的复杂度都被 Spring AI Alibaba 吸收。OryxOS 在其上做一层薄包装，把 Spring AI 的 `ChatClient` 转成 OryxOS 内部的 `ProviderService` 抽象。

关键点：**Provider 名到 ChatModel 的显式映射**。不能靠扫描容器里所有 `ChatModel` 来区分 Provider，必须维护显式映射表。

### 能力二：ReAct 循环

ReAct 循环是 OryxOS 最核心的一段代码。输入一条用户消息，输出 Agent 的最终响应，中间可能调用若干次 LLM 和若干次 Tool。

核心循环逻辑精简，约数十行 Java，不依赖 Spring AI 的 Agent 抽象。

### 能力三：Memory 三层记忆

Memory 做成三层记忆的统一门面，对 ReAct 循环只暴露一个 `MemoryService` 接口：

- **会话记忆**：SQLite 持久化，重启可恢复
- **长期记忆**：`MEMORY.md` 文件，通过 `save_memory` / `recall_memory` 两个内置 Tool 读写
- **情景记忆**：扩展阶段补齐

### 能力四：Tool 体系

核心阶段 Tool 相关合并为一个 `oryxos-tool` 模块（内置 Tool、MCP Client、`ToolRegistry`、Sandbox 都在里面），不拆成 builtin/skill/mcp 三个模块。

`SKILL.md` 不是可执行 Tool，而是注入 system prompt 的指令模板，归 `ContextLoader` 加载。

### 能力五：Web Service

对外两个入口：CLI Channel 用于本地交互和调试，Web Service 用于业务系统通过 REST API 集成。

核心阶段 10 个端点，全部在 `/api/v1` 前缀下。

## 数据持久化

核心阶段选 SQLite 加 Spring Data JPA 做关系型持久化，`MEMORY.md` 文件加关键词检索做长期记忆。

**核心表三张：** `sessions`、`tool_invocations`、`llm_calls`

其中审计相关的 `tool_invocations` 和 `llm_calls` 两张表在核心阶段就做写入，让可审计这个差异化能力的数据地基在 day one 就立起来。

## Maven 模块结构

由 **9 个模块**组成：

| 模块 | 对应 | 职责 |
|------|------|------|
| `oryxos-core` | 核心引擎 | `ReActLoop`、`PromptBuilder`、`ToolExecutor`、`ContextLoader`、Session、Profile、`OryxTool` 等抽象 |
| `oryxos-provider` | 能力一 | `ProviderService`、Function Calling 适配、provider name 映射 |
| `oryxos-memory` | 能力三 | `MemoryService`（三层统一门面）、`LongTermMemory`、`MemoryTools` |
| `oryxos-tool` | 能力四 | 内置 Tool、MCP Client、`ToolRegistry`、`SandboxChecker`（三合一） |
| `oryxos-web` | 能力五 | `WebServer`、六个 ApiController、`GlobalExceptionHandler`、OpenAPI 文档 |
| `oryxos-channel-cli` | 支撑 | CLI Channel 实现 |
| `oryxos-storage` | 支撑 | SQLite 存储层，含 `sessions`、`tool_invocations`、`llm_calls` 三张表 |
| `oryxos-cli` | 支撑 | Picocli 命令行入口（12 个子命令） |
| `oryxos-boot` | 支撑 | Spring Boot 启动模块，把所有依赖打成 fat JAR |

## 完整文档

> 📄 完整技术方案文档约 4 万字，包含详细的数据模型、实施节奏和性能考虑。

**[在 GitHub 上查看完整文档](https://github.com/XiaoDHuang/oryxos/blob/main/docs/TechnicalSolution.md)**
