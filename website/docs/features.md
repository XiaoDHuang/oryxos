---
title: 核心能力
---

# 核心能力

核心阶段优先做五个能力，都属于"让单个 Agent 跑得好"的运行时内核层。基于这五个能力可以组合出企业里大量真实需求，OryxOS 本身不绑定具体业务。

| # | 能力 | 一句话 |
|---|---|---|
| 1 | **对接 LLM** | 通过 Provider 抽象对接 DeepSeek、通义、Kimi、智谱、混元、豆包、Anthropic、OpenAI 等主流大模型，Agent 不感知具体调的是哪家，运行时切换无 lock-in |
| 2 | **ReAct 循环** | Agent 的核心工作机制：LLM 思考是否调用工具、调用后看结果、再决定下一步，直到给出最终响应 |
| 3 | **Memory 三层记忆** | 会话记忆（当前对话）+ 长期记忆（`MEMORY.md`，跨对话保留用户偏好和项目背景）+ 情景记忆（扩展阶段） |
| 4 | **Tool 体系** | 内置文件 / Shell / HTTP 工具，Plugin Tool 三档接入：零代码写 `SKILL.md` 复用 MCP、轻代码自写 MCP server、重代码 `@Tool` 注解写 Spring Bean |
| 5 | **Web Service** | 完整 REST API 对外暴露，业务系统一句 HTTP 调用就能用上 Agent，是企业把 AI 能力嵌入已有系统的唯一通道 |

五个能力像五个齿轮，组合起来就是全渠道客服、运维助手、研发助手、知识管理、销售助手、数据分析等场景——这些场景不需要 OryxOS 单独做模块，业务方在 OryxOS 上配 Profile、写 Plugin Tool、调 Web Service 就能落地。

## 能力一：对接 LLM

OryxOS 通过 Provider 抽象层对接主流大模型。所有 LLM 调用通过 Provider 接口走，Agent 不感知具体调的是哪家。

- 基于 Spring AI Alibaba 的 `ChatClient` 实现，复用十余个主流 LLM connector
- 显式维护 provider name → `ChatModel` 映射表，多 Provider 并存无歧义
- 核心阶段不做 fallback 和 hedge racing，Provider 故障直接报错
- 每次 LLM 调用记录 token 使用量、Provider、模型，落 `llm_calls` 审计表

## 能力二：ReAct 循环

ReAct 是 Agent 的核心工作机制。LLM 思考是否调用工具、调用之后看结果、再决定下一步，直到给出最终响应。

- 自实现 ReAct loop，不用 Spring AI 的 Agent 抽象
- 默认最大迭代 10 次，可在 Profile 覆盖
- 消息累积进 Session，完整记录 LLM 调用链和 Tool 调用链
- Tool 调用失败按指数退避重试，最多 3 次

## 能力三：Memory 三层记忆

Agent 跨对话保留状态的能力。

- **会话记忆**：当前对话完整历史，SQLite 持久化，重启可恢复
- **长期记忆**：`.oryxos/memory/MEMORY.md` 文件，Agent 通过 `save_memory` / `recall_memory` 两个内置 Tool 读写
- **情景记忆**：任务过程中的修改文件、决策、成果，扩展阶段补齐

## 能力四：Tool 体系

Tool 分两类，这两类的区分是 OryxOS 让业务方扩展的核心机制。

**内置 Tool**：`read_file` / `write_file` / `list_dir` / `shell` / `http_get` / `http_post` / `save_memory` / `recall_memory`

**Plugin Tool 三档接入**：

1. **零代码**：写 `SKILL.md` 描述意图，复用社区现成 MCP server
2. **轻代码**：用任何语言自己写 MCP server
3. **重代码**：用 `@Tool` 注解写 Java Spring Bean

## 能力五：Web Service

OryxOS 的对外完整门面，业务系统通过 REST API 接入。

核心阶段 10 个关键端点：

| 端点 | 说明 |
|---|---|
| `POST /api/v1/sessions` | 创建会话 |
| `POST /api/v1/sessions/{id}/messages` | 发消息 |
| `GET /api/v1/sessions/{id}` | 查询会话历史 |
| `DELETE /api/v1/sessions/{id}` | 归档会话 |
| `POST /api/v1/agents/{name}/invoke` | Agent 无状态调用 |
| `GET /api/v1/profiles` | 列 Profile |
| `GET /api/v1/memory` | 查长期记忆 |
| `GET /api/v1/tools` | 列可用 Tool |
| `GET /api/v1/health` | 健康检查 |
| `GET /api/v1/info` | 系统信息 |

更多设计细节见 [需求分析文档](/docs/demand-analysis) 和 [技术方案文档](/docs/technical-solution)。
