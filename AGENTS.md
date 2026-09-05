# AGENTS.md

本文件是 OryxOS 仓库面向 **Claude Code / Codex / Cursor** 的统一 Agent 指令（单一事实来源）。内容提炼自 `docs/` 下四份文档（`IndustryResearch.md` 业界调研、`DemandAnalysis.md` 需求分析、`TechnicalSolution.md` 技术方案、`AiProgrammingGuide.md` AI 编程实施指南），四份文档是本项目唯一的事实来源，本文件不重复论证，只提炼成可直接执行的编码约束。**四份文档跟本文件冲突时，以四份文档为准，并回来更新本文件（再经软链同步到 `CLAUDE.md` 等）。**

跨工具目录与 Skills 说明见 [`.agents/README.md`](.agents/README.md)。
## 项目现状

Maven 9 模块与 Provider、ReAct、CLI/Session、Notify、Tool 已有实现，006 文件式 Memory 已在 `3d60ee0` 归档。007 Memory 三后端已于 2026-09-04 归档（`d040713`，85/85，R1–R5 全通过）：Markdown 默认兼容、SQLite 本地后端、显式可选的自托管 Mem0（受控 Python 适配器 + 独立 PG/pgvector，默认关闭）；实际任务与证据见 `specs/007-memory-backends/tasks.md`、`acceptance.md`，不能把本地后端绿灯当作 007 完成。008（第 24 节）Sandbox 白名单实现已随 `024-lesson24-sandbox` 分支交付：`WhitelistSandbox` 三类全路由（文件路径目录边界、Shell 首 token、HTTP 精确域名委托 007 既有实现），空名单=全拒绝，Sandbox 拒绝原因经 `AnnotatedToolAdapter` 转为不可重试失败进 `tool_invocations` 并回填模型；证据见 `specs/008-sandbox-whitelist/`。009（第 25 节）定时任务已交付：`AgentScheduler`（oryxos-core，纯 POJO）把 Profile 的 `schedules`（强类型 `ScheduleConfig`：id/cron/zone/message）经 `CronTrigger`（cron+显式时区）动态注册进 Spring `ThreadPoolTaskScheduler`，按规则 id 进程内 ReentrantLock 防重叠、失败只记日志 finally 放锁、固定三元组 ("scheduler","scheduler",profileName) 复用 Session 走既有审计；仅常驻模式注册——`oryxos.scheduler.enabled=true` 由 serve/gateway 启动参数传入并条件装配（chat 无此 Bean）；证据见 `specs/009-agent-scheduler/`。9 模块仍是默认基线；任何模块新增、删除、改名或职责迁移必须先写入 feature plan、获得用户显式批准，并同步本文件与 `docs/TechnicalSolution.md` 后才能实施。

## 一句话理解 OryxOS

OryxOS 是基于 Java 实现的、面向企业场景的 Agent OS。装在企业自己的 K8s 或服务器上，作为统一底座跑多个业务 Agent（运维、客服、HR、销售、知识管理等），共享渠道接入、模型路由、工具调用、记忆系统、沙箱执行能力。数据完全留在企业基础设施内，不锁定任何云生态。定位对标业界的 OpenClaw（Node.js，偏个人）、Hermes Agent（Python，偏团队），OryxOS 是 Java 生态里第一个把"Agent OS"作为定位的项目。

## 最关键的分层判断

> **Agent OS ≠ agent runtime。** runtime 是让单个 Agent 跑起来的执行内核（LLM 调用、工具执行、上下文管理、循环控制）；Agent OS 在 runtime 之上管理多个 Agent 的生命周期、统一接入、统一记忆、多租户、审计。runtime 让一个 Agent 跑起来，Agent OS 让一群 Agent 在企业里被管起来。

OryxOS 的交付分两段，写任何代码前先确认自己在哪一段：

- **核心阶段**（当前唯一在做的事）：把 Agent OS 的**运行时内核**用 Java 做扎实，对齐业界开源 Agent OS 的基础层。范围只覆盖运行时内核的最短跑通链路。
- **扩展阶段 / 社区共建**：多租户、SSO、完整审计查询、Tool Policy、Web 仪表板等企业级治理层，是 OryxOS 真正的差异化终局，**不在核心阶段做**。

看到"这个功能是不是该做"这类问题时，先问：它是运行时内核的最短链路，还是治理层？后者一律推到扩展功能，不要在核心阶段的代码里抢跑。

## 两条容易架构错方向的项目底线

- **Agent 是"配置"出来的，不是"写代码"写出来的。** `Profile` 是统一运行时契约，核心阶段由 Profile YAML 加载；未来若引入 `AGENT.md`/Agent 目录作为作者与分发格式，也必须派生到同一 `Profile`，不得形成第二套运行时模型。看到"加一个客服 Agent""加一个运维 Agent"这类需求，绝不能写 `CustomerServiceAgent.java`；正确做法是新增声明式配置与上下文资产。
- **OryxOS 做运行时，不做编排。** 不做可视化 workflow 编排、不做复杂任务分解、不做多 Agent 显式协作。需要复杂 workflow 的场景，是让 Dify 之类的编排平台跑在 OryxOS 之上（把 OryxOS 当后端），而不是在 OryxOS 里造一个编排引擎。ReAct 循环靠 LLM 在运行时动态决定下一步，本身就替代了预先编排。

## 技术栈与工程结构

JDK 21 + Spring Boot 3.x + Spring AI / Spring AI Alibaba + 自实现 ReAct loop + SQLite (Spring Data JPA) + Picocli 命令行 + SnakeYAML + MCP Java SDK + Logback/SLF4J。单体应用，可执行 fat JAR，单二进制部署。

**Maven 多模块，当前核心阶段默认保持以下 9 个模块**。未经 feature plan 论证、用户批准和文档同步，不要拆多、合并或迁移职责：

| 模块 | 对应能力 | 职责 |
|---|---|---|
| `oryxos-core` | 引擎 | `ReActLoop`、`PromptBuilder`、`ToolExecutor`、`ContextLoader`、`OryxTool`/`MemoryService` 端口、`MemoryScope`、Session/Profile 数据结构 |
| `oryxos-provider` | 能力一 对接 LLM | `ProviderService`、provider name 到 `ChatModel` 显式映射、Function Calling 适配 |
| `oryxos-memory` | 能力三 Memory | `MemoryServiceImpl`、兼容的 `LongTermMemory`、`MemoryTools`；007 新增 `LongTermMemoryStore`、Markdown/SQLite/Mem0 三实现与选择配置 |
| `oryxos-tool` | 能力四 Tool | 内置 Tool（File/Shell/Http）、MCP Client、`ToolRegistry`、`SandboxChecker`，**三合一，不拆分** |
| `oryxos-web` | 能力五 Web Service | `WebServer`、六个 ApiController、`GlobalExceptionHandler`、OpenAPI |
| `oryxos-channel-cli` | 支撑 | CLI Channel（`oryxos chat`） |
| `oryxos-storage` | 支撑 | SQLite 层：既有 `sessions`、`tool_invocations`、`llm_calls`；007 增加 `memory_entries` 实体、仓储与显式迁移 |
| `oryxos-cli` | 支撑 | Picocli 12 个子命令入口 |
| `oryxos-boot` | 支撑 | Spring Boot 启动、打 fat JAR |

## 构建、运行与测试

工程骨架已实现，以下是核心阶段约定命令；具体功能可用性与验收状态以对应 feature 台账为准。

```bash
# 构建：Maven 多模块，产物是 oryxos-boot 下的可执行 fat JAR
mvn clean package

# 只编译/测试单个模块（-am 连带构建其依赖）
mvn -pl oryxos-core -am test

# 跑全部测试
mvn test

# 运行：通过 fat JAR 调 CLI 子命令（12 个命令见下）
java -jar oryxos-boot/target/oryxos-boot-*.jar init
java -jar oryxos-boot/target/oryxos-boot-*.jar chat --profile default
java -jar oryxos-boot/target/oryxos-boot-*.jar serve --port 8080
```

约定与注意：

- **启动分两类命令**（性能考虑）：不需要 LLM 的命令（`init`、`profile list`）直接走文件操作、不启动 Spring 上下文以求快；需要 LLM 调用的命令（`chat`、`serve`、`gateway`）才启动 Spring 上下文。实现 `oryxos-cli` 时按这个区分，不要所有命令都拉起 Spring。
- **API key 从环境变量注入**，跑起来前先 `export DEEPSEEK_API_KEY=...` 之类，Profile YAML 里用 `${ENV_VAR}` 占位。
- 扩展阶段才引入 GraalVM Native Image 编译原生二进制，核心阶段不碰。

## 测试约定

- **每个核心功能模块至少要有一个端到端测试用例覆盖**（需求文档第 13 章的功能验收硬要求）。写完一个能力先补它的端到端用例，再往下走。
- **五个验收 Demo 是核心功能发布的硬条件**（见下文「五个验收 Demo」），每个 Demo 对应一个 user story 的 acceptance criteria，跑通它比追求功能完备优先。
- 性能验收目标（压测口径）：单节点 10 个 Agent 稳定运行 4 小时、100 并发 Session、Session 创建 P99 < 200ms、内部转发开销 < 50ms。不达标不阻塞发布，但要在扩展阶段优化，别在核心阶段为压这些数字过度设计。

## 七个关键技术决策（不可违反）

这些是《技术方案》第 1.1 节定的原则，也是《AI 编程实施指南》里点名"AI agent 最容易写错"的地方，写代码时逐条对照：

1. **自己实现 ReAct loop**，不用 Spring AI 的 Agent 抽象。核心循环控制权必须完全在 OryxOS 手里。
2. **Spring AI 只用一半**：只用它的 Provider 协议转换和 `@Tool` 的 JSON Schema 生成，**必须禁用它的自动 tool 执行**。Tool 的实际调度和执行完全由 `ReActLoop` + `ToolExecutor` 控制。忘记禁用会导致 tool 被调两次——这是文档反复强调的头号踩坑点。
3. **同步阻塞执行模型 + Java 21 virtual thread**，核心阶段不做响应式编程、不做流式 SSE。
4. **Tool 注册用 `@Tool` 注解 + `OryxTool` 抽象层**，让 ReAct loop 不感知 Tool 来源（内置/MCP/Plugin 统一包装成 `OryxTool`）。
5. **HTTP 层用 Spring MVC + virtual thread**，不用 WebFlux。
6. **Sandbox 用 Path/Pattern 白名单**做应用层校验，**禁止使用 Java SecurityManager**（JDK 17 起废弃，JDK 21 已不可用，跟本项目 JDK 21+ 要求直接冲突）。
7. **Session/审计持久化用 SQLite + Spring Data JPA**，长期记忆默认 `MEMORY.md`，007 增加 SQLite / 自托管 Mem0 显式可选后端；`tool_invocations` 和 `llm_calls` **核心阶段就要落库**。选择外部记忆后端不免除数据不出域、网络白名单和审计义务。

## Provider 映射的一个具体陷阱

Spring AI Alibaba 配多个 Provider 时，容器里会有多个 `ChatModel` Bean，**不能靠扫描容器里所有 `ChatModel` 来区分谁是 deepseek、谁是 kimi**（Bean 类型相同，Bean name 未必等于 provider name）。必须维护一份显式的 provider name → `ChatModel` 映射表，Profile 通过 provider name 引用。这是文档里第二个反复强调的踩坑点。

## 五大核心能力（运行时内核的骨架）

Provider、Memory、Tool 三个能力供养 ReAct 循环这个引擎，引擎跑出的能力通过 CLI 和 Web Service 两个入口对外。写代码时按这个协作关系摆放依赖，不要让 ReAct 循环反向依赖 Web 层。

| 能力 | 一句话 | 核心阶段范围 | 核心阶段不做 |
|---|---|---|---|
| 一 对接 LLM | Provider 抽象包一层 `ChatClient`，Agent 不感知具体模型 | 显式 name 映射、至少跑通 DeepSeek/Kimi 一家 | fallback、circuit breaker、hedge racing、adaptive routing |
| 二 ReAct 循环 | Reason+Act，OryxOS 最核心的一段代码，约数十行 Java | 顺序执行 Tool 调用、MAX_ITERATIONS 默认 10（Profile 可覆盖）、消息累积进 Session | Tool 并行调用、上下文动态压缩、Agent 间任务委托 |
| 三 Memory 三层记忆 | Agent 记得住偏好和历史 | 会话 SQLite；长期默认 Markdown，007 增加 SQLite/自托管 Mem0；两个 Tool 不变，核心全量、归档按后端窗口注入 | OryxOS 自动提炼、自建向量索引、情景记忆、Memory Wiki、图谱与压缩 |
| 四 Tool 体系 | 内置工具 + Plugin Tool 三档接入 | 内置：File 组 `read_file`/`write_file`/`list_dir` + Shell 组 `shell` + HTTP 组 `http_get`/`http_post`（共 6 个），加 Memory 的 `save_memory`/`recall_memory`（归 memory 模块但作为内置 Tool 注册）；Plugin 三档：零代码 `SKILL.md`+MCP（主推）、轻代码自写 MCP server、重代码 `@Tool` 注解；MCP Client 先做 stdio transport，SSE 放扩展 | Tool Policy、Tool LRU 加载、完整容器级 sandbox、MCP Server 暴露 |
| 五 Web Service | REST API 对外唯一门面 | 核心 10 个端点（见下） | 认证/RBAC、SSE 流式、WebSocket、限流 |

**核心 10 个端点**（不要多做也不要漏做）：`POST /sessions`、`POST /sessions/{id}/messages`、`GET /sessions/{id}`、`DELETE /sessions/{id}`、`POST /agents/{name}/invoke`、`GET /profiles`、`GET /memory`、`GET /tools`、`GET /health`、`GET /info`。都在 `/api/v1` 前缀下。

## 数据模型（写实体/DTO 时对照，不要自己发明字段）

**Profile（YAML，`.oryxos/profiles/*.yaml`）：**

`name`、`description`、`identity`（`agent_name`、`prompt` 或 `prompt_file`）、`provider`（`name`、`model`、`temperature`，可选 `fallback`——核心阶段不实现 fallback 逻辑但字段可以先留）、`tools`、`skills`、`mcp_servers`、`channels`、`notify_channels`（每项带 `type` 与渠道特定配置如 `url`，供 `NotifyTools` 用，见技术方案 §6.8）、`schedules`（定时配置，见 §8.5）、`bootstrap`、`settings`（`max_iterations` 默认 10、`max_history_turns` 默认 20）、`created_at`/`updated_at`。敏感值（API key）在 YAML 里用 `${ENV_VAR}` 占位，`ConfigLoader` 从环境变量解析，禁止明文写死。

**Session（落 SQLite `sessions` 表）：**

`session_id`（channel+user+profile 联合生成）、`profile_name`、`channel`、`user_id`、`messages_json`（对话历史，每条含 `role`/`content`/`timestamp`/`tool_calls`）、`context_state`、`status`（`active`/`archived`）、`created_at`/`last_active_at`/`archived_at`。

**tool_invocations（审计表，核心阶段就要写入）：**

`invocation_id`/`session_id`/`profile_name`、`tool_name`、`parameters`（JSON）、`status`（`running`/`completed`/`failed`/`timeout`）、`result`/`error`、`started_at`/`completed_at`、`token_cost`。

**llm_calls（审计表，核心阶段就要写入）：**

`call_id`/`session_id`、`provider`/`model`、`prompt_tokens`/`completion_tokens`/`total_tokens`、`latency_ms`、`status`、`started_at`/`completed_at`。

**Memory：** Markdown 默认用分区 `MEMORY.md`，不为文件添加数据库 schema。007 SQLite 后端新增 `memory_entries`：`id`（INTEGER 自增主键）、`scope`（VARCHAR(16)，CORE/ARCHIVAL）、`content`（TEXT）、`created_at`（TIMESTAMP），后三项非空，索引 `idx_memory_scope`；不增加用户/租户字段。Mem0 字段按 plan 核验后的自托管协议映射，不发明第二套 Session。

## Memory 三后端实施边界（007）

- 当前实施进度：007 已归档（85/85，R1–R5 全通过，提交 2a63e58/5333e77/83a8a50/3667d54/d040713）。Mem0 生产启用仍需企业获准内网环境与部署方 Secret/库/出口策略；真实模型验收用的是用户批准的本地 Ollama 替代环境（mistral-nemo:12b + bge-m3，temperature=0，本机回环）。

- 2026-08-31用户批准对固定Mem0 1.0.11回移官方CVE-2026-7597补丁，保留原提炼算法；仅FAISS源码与诚实构建元数据可变化，受控版本为1.0.11+oryx.1。来源/产物摘要、真实反序列化回归及完整原始扫描必须保留；只对已证实修复的本构建目标告警作处置，未知告警仍失败，不使用全局忽略。细则见007的SDK安全回移契约。用户最新要求代码、测试及最终回归全部由主模型执行，不再调度Spark。

- 保持 core 的 `List<Message> buildContext(Session, int)`、`remember(String, MemoryScope)`、`List<String> recall(String)` 签名；存储抽象/实现留在 memory，实体/仓储在 storage，仍为 9 模块。
- `memory.backend` 在 `application.yaml` 启动时选 `markdown`（默认）/`sqlite`/`mem0`，重启生效。非法值失败、禁用后端零访问；切换不隐式迁移、删除、双写或静默降级。
- scope 是 CORE/ARCHIVAL 分区，不改变当前工作区级共享边界；Mem0 的远端身份在 plan 明确映射，不擅自改为按 Profile/用户隔离。
- 核心全量、不参与归档检索；Markdown 注入归档最近 4000 Java char，SQLite 最近 100 条。二者检索全量归档关键词；Mem0 允许语义检索，但分页、窗口、scope 和核心完整性必须验证，不能混用三后端断言。
- 保留 006 `LongTermMemory` 的既有行为及回归断言；裁剪只影响注入，不删原始历史；保存成功后下一轮可见，远端异步处理必须有界等待或失败，不以最终一致静默放宽契约。
- Mem0 默认关闭，仅自托管且完整数据路径（服务、模型、embedding、存储）和审计来源验证后可启用。每次涉外 I/O 前校验允许目标，缺安全接线拒绝；凭证用环境变量，不设默认云地址。
- 现有 `tool → memory → core/storage`，memory 不得依赖 tool 中的 Sandbox。用户已批准 007 的 `MemoryOutboundGuard`（memory）、`HttpWhitelistSandbox`（tool）及 boot 组合接线，不可用空检查绕过；008 已在 HTTP 白名单之上交付 `WhitelistSandbox` 三类全路由（`file.allowed_paths`/`shell.allowed_commands`/`http.allowed_domains`，空=全拒绝），HTTP_REQUEST 仍委托 007 精确匹配实现，007 接线语义不变。
- 不新增 OryxOS 自动保存触发器。007 clarify 已获用户批准：Mem0 在显式保存归档时自动提炼、合并和替换，原始输入与被合并/替换旧归档持久保留且可追溯；常规召回/自动归档注入只读当前有效条目，不读历史副本。保存成功须同时满足有效状态可读和历史保全；核心与本地后端原文规则不变。历史落位、失败恢复及数据/审计路径仍须在 plan 核验；Mem0 内部 LLM 调用不能假称已进 OryxOS `llm_calls`。
- 006 原规格保留为历史基线；007 共同测试须经过真实适配器，远端可替换传输不可替换成假 Store。范围记录见 `docs/decisions/007-memory-backends-scope.md`，未完成 plan 核验不得实现 Mem0。

- 用户已批准并正在实现 `integrations/mem0-adapter/` 受控 Python 组件，随 Mem0 部署、不新增 Maven 模块；暂存、协议、隔离PG事务/查询及API分层验证已完成，真实部署/模型与全链路验收未完成。固定 SDK 的提炼只操作请求级暂存；外部 PostgreSQL/pgvector 中 `memory_namespaces`、`memory_operations`、`memory_current`、`memory_versions`、`memory_call_audits` 承载原子提交与历史。不得用原版 REST 直连替代自有 `oryx-memory-v1` 协议。
- 用户于2026-09-01批准远端表示边界：Mem0的content/query及模型生成内容拒绝U+0000，避免PG TEXT无法保全原文；协议请求hash的字段间NUL分隔符保留，Markdown/SQLite输入能力不变。不得删除、替换或编码改写NUL后虚报保存成功。
- Java 的 `MemoryOperationException` 只携带固定分类与操作 UUID，工具适配器映射为不可重试失败；不得把错误字符串返回成工具成功。最终审计 status 沿用 failed，并在错误字段区别 timeout/outcome_unknown，不擅改审计端口或表。
- 007 plan 的设计门禁与运行验收分开：设计契约闭合后可按 tasks 实现并验证；部署启用前必须完成真实组件、完整数据路径、Python/镜像安全与审计证据检查。批准范围不等于实际验收通过，不能仅凭 capabilities 声明或假测试放行。

## 关键流程（实现 ReActLoop / Controller 时对照步骤，不要自己发明顺序）

**消息处理流程：** Channel 收到消息（CLI 输入 / HTTP 调用）→ 转成内部统一格式带用户身份 → 查/建 Session → `PromptBuilder` 组装 prompt（system prompt + Bootstrap + Skill + Memory + 对话历史 + 可用 Tool 列表）→ 调 LLM → 若有 Tool 调用则 `ToolExecutor` 执行并把结果回填继续循环 → 无 Tool 调用则返回最终响应 → 全程写结构化日志。

**Tool 调用流程：** LLM 通过 Function Calling 指明 Tool 和参数 → `ToolRegistry` 查找对应 `OryxTool` → 参数校验 + `SandboxChecker` 白名单校验 → 执行（内置 Tool 进程内执行，MCP Tool 走 `McpToolAdapter` 转发）→ 包装成 `ToolResult`（成功标识、内容、错误信息、可重试标识）回传 → 写入 `tool_invocations`。

**Session 上下文管理流程：** 首次消息按 channel+user+profile 查活跃 Session，没有则新建 → 后续消息追加 messages → 超过 `max_history_turns` 简单截断保留近期对话（不做总结压缩）→ 超时无消息则结束归档。

## 非功能红线（这些数字来自需求文档第 8 章，不要脱离数字去优化）

- **性能：** 单节点 ≥10 个 Agent、≥100 并发 Session；Session 创建 P99 < 200ms；OryxOS 内部转发开销（不含 LLM 本身延迟）< 50ms。
- **可靠性：** 已注册 Profile 和已写入 Session 不丢；LLM Provider 故障核心阶段直接报错（不做 fallback）；Tool 调用失败按指数退避重试，**最多 3 次**。
- **可运维性：** Profile 修改后重启生效（不做热更新）；支持物理机/虚拟机/Docker/K8s 部署。
- **安全基线：** API 支持 HTTPS；敏感配置（API key、DB 密码、Tool 凭证）不允许明文写配置文件；Tool 调用必须过白名单校验（路径/命令/域名）。
- **合规：** OryxOS 不主动外发任何数据，这一条是硬约束，任何新功能设计时都不能引入默认上报或遥测。
- **兼容性：** JDK 21+（Spring Boot 3.x 要求）；OS 支持 Linux 主流发行版（Ubuntu 22.04+、CentOS 8+、Debian 11+、Alibaba Cloud Linux 3、Rocky Linux）。**OpenAI 兼容协议是事实标准**，只要 Provider 实现这套协议就能直接接、不用专门适配。**核心阶段先把 OpenAI 协议这条线（DeepSeek、Kimi）跑稳**，其他 Provider（通义、智谱、混元、豆包等）在扩展阶段每接一家做完整回归——不要核心阶段就铺开接所有 Provider（Spring AI Alibaba 各 connector 在 Function Calling、Stream、Token 计数、错误码上可能有细节不一致）。

## Web Service 具体约束（实现 Controller 时对照，别漏了限制）

- 错误响应统一 JSON：`errorCode`、`message`、`timestamp`；HTTP 状态码：400 参数错误、404 资源不存在、500 内部错误、503 Provider 故障。
- 单条消息最大 **32KB**；Session 历史查询最多返回**最近 100 条**。
- Agent 调用最长 **60 秒**超时，超时返回 **504**。
- CORS 核心阶段**开放所有源**（方便调试），不要现在就加白名单限制——那是扩展阶段的事，提前加会跟"核心阶段不做认证"的假设冲突增加调试成本。
- 核心阶段**不做**：认证（假设内网无认证）、SSE 流式、WebSocket、RBAC、限流。看到这些需求直接答"扩展阶段"，不要在核心阶段悄悄实现一个简化版。
- Sandbox 白名单配置项固定放在 `application.yaml`：`file.allowed_paths`、`shell.allowed_commands`、`http.allowed_domains`。

## 未决事项（文档明确留白，遇到时先向用户确认，不要自己拍板）

以下几点《需求文档》和《技术方案》原文写的是"待决议"，不是遗漏：

- **Provider 抽象层次**：直接用 Spring AI 的 `ChatClient`，还是在其上再包一层 OryxOS 自己的抽象？两个方案都合理，动手前先确认选哪个。
- **底层存储 SQLite 还是 H2**：技术方案倾向 SQLite（决策七），但原需求文档列了两个选项都在考虑范围，遇到明确冲突以最新的《技术方案》SQLite 为准，但如果用户有新意见要留出讨论空间。
- **Bootstrap 文件加载顺序**：`AGENTS.md`、`SOUL.md`、`USER.md` 拼进 system prompt 的先后顺序和权重没有定案，实现 `ContextLoader` 时如果没有明确指示，先用「AGENTS.md → SOUL.md → USER.md」这个书写顺序占位，并在代码注释里标注这是待定顺序。
- **GraalVM Native Image 引入时机**：核心阶段还是扩展阶段引入，尚未决议，核心阶段默认按普通 Spring Boot 启动实现，不要提前做 AOT 相关的编译期配置改动。

## 常见踩坑清单（写完代码后逐条自查）

- [ ] 有没有启用 Spring AI 的自动 tool 执行？必须禁用。
- [ ] Provider 的 name 映射是不是靠类型扫描猜的？必须显式映射表。
- [ ] Tool 相关代码是不是未经批准又拆成了 builtin/skill/mcp 多个模块？当前基线必须合并进 `oryxos-tool`；未来拆分也必须走模块演进审批流程。
- [ ] `SkillLoader`/`SKILL.md` 加载逻辑是不是被放进了 Tool 模块？`SKILL.md` 是 prompt 输入不是 Tool，归 `oryxos-core` 的 `ContextLoader`，跟 Bootstrap 文件同类处理。
- [ ] `tool_invocations`、`llm_calls` 是不是只写了日志没落库？核心阶段必须写 SQLite 表。
- [ ] 有没有用到 `SecurityManager`？禁止，用 Path/Pattern 白名单代替。
- [ ] SQLite 的表结构变更是不是依赖 `hibernate.ddl-auto=update`？SQLite 的 ALTER TABLE 能力弱，表结构演进要手动维护脚本或引入 Flyway/Liquibase，不要依赖自动迁移。
- [ ] `MEMORY.md`（长期记忆，Agent 写）跟 `USER.md`（Bootstrap，用户写）是不是弄混了？前者 OryxOS 读写，后者 OryxOS 只读。
- [ ] Session 上下文超长是不是引入了复杂压缩逻辑？核心阶段只做简单截断保留近期对话（默认最近 20 轮，Profile 可配）。

## 术语速查

| 术语 | 定义 |
|---|---|
| Agent | 一个具象智能体，通过 Profile 配置出来，不是写代码写出来的 |
| Profile | 一个 Agent 的完整配置（YAML）：identity、provider、tools、channels、bootstrap |
| Provider | LLM API 的统一抽象 |
| ReAct 循环 | Reason+Act，Agent 核心工作机制 |
| Tool | 内置 Tool（OryxOS 自带）/ Plugin Tool（业务方扩展，三档） |
| Memory | 会话记忆（SQLite）+ 长期记忆（默认 Markdown，007 可选 SQLite/自托管 Mem0）+ 情景记忆（扩展阶段） |
| Channel | 消息接入渠道，核心阶段只有 CLI；HTTP 不算 Channel，归 Web Service |
| Session | 一次对话的上下文容器，Channel+User+Profile 联合标识 |
| Sandbox | Tool 执行隔离，核心阶段是应用层白名单 |
| Bootstrap | `AGENTS.md`（项目行为说明）、`SOUL.md`（人格）、`USER.md`（用户偏好），加载进 system prompt |
| Workspace | `.oryxos/` 工作目录 |

## `.oryxos/` 工作区结构

`oryxos init` 创建：`profiles/`、`sessions/`、`skills/`、`logs/`、`tools/`、`memory/MEMORY.md`、`mcp_servers.yaml`、`AGENTS.md`、`SOUL.md`、`USER.md`、`oryxos.db`（SQLite）、`profiles/default.yaml`（默认 Profile）。默认 Profile 用最简配置让用户立刻可用：一个默认 LLM Provider + 几个基础 Tool + CLI Channel。

## 关键配置文件格式（实现加载器时对照，别自创字段）

- **`SKILL.md`**：带 frontmatter（`name`、`description`、`trigger`、`required_tools`）+ 任务说明正文的 markdown。由 `oryxos-core` 的 `ContextLoader` 加载进 system prompt，**不是可执行 Tool**，OryxOS 不解析步骤、不做工作流引擎，一切交给 LLM 理解。Profile 用 `skills` 字段引用。
- **`mcp_servers.yaml`**：声明每个 MCP server 的 `name`、`transport`、`command`、`env`。OryxOS 启动时连接、调 `tools/list` 拿工具列表、包装成 `OryxTool` 注册进 `ToolRegistry`。Profile 用 `mcp_servers` 字段引用。核心阶段只做 stdio transport。
- **`application.yaml`**：Provider 的 API key / base URL、Sandbox 白名单（`file.allowed_paths` / `shell.allowed_commands` / `http.allowed_domains`）、SQLite 数据源（指向 `.oryxos/oryxos.db`）；007 新增 `memory.backend`，Mem0 专属参数名和必填校验由 plan 锁定。

## 项目主页（核心阶段交付物，第四周做）

OryxOS 作为开源项目需要一个独立主页作为对外门面，讲清楚它是什么、五大能力是什么、怎么快速开始。技术栈用 VitePress / Astro / Docusaurus 之类的静态站点生成器。它是**项目方的统一交付物**（不是社区共建），跟核心代码同期发布，作为 OryxOS 1.0 对外亮相的一部分。不要把它当成扩展阶段的事往后拖。

## 三种运行模式 / 12 个命令

`oryxos chat`（交互对话）、`oryxos serve`（Web Service）、`oryxos gateway`（多渠道守护进程）共享同一份 Profile 配置和 Session 存储。命令行共 12 个：`init`、`status`、`chat`、`serve`、`gateway`、`profile list/create/show/delete`、`provider list`、`tool list`、`session list`。

## 五个验收 Demo（每个核心能力至少对应一个端到端场景）

1. 对接 LLM+ReAct：查天气写日报
2. Memory：跨对话记住偏好（Spring Boot/K8s）
3. Plugin Tool+MCP：零代码 `SKILL.md` 跑通 daily PR digest
4. Web Service 同步调用：创建 Session→发消息→查历史→归档
5. Web Service 多端点联动：`info`→`profiles`→`tools`→`invoke`→`memory`

写完一个能力，优先跑通对应 demo 验证，而不是追求功能完备。

## 开发流程

主体开发用 **Spec-Kit**（constitution → specify → plan → tasks → implement），按 5 个 user story 组织，依赖顺序 `US-1 对接LLM → US-2 ReAct → (US-3 Memory ∥ US-4 Plugin Tool) → US-5 Web Service`，对应需求/技术方案定的 4 周 / 每周 3 小时 / 合计 12 小时节奏，每周末有可演示成果。每个 user story 完成后跑一次 `/speckit.analyze` 检查漂移（不能省略），并 **git commit 标记该 user story 完成**，方便随时回退到稳定状态。

Constitution（`.specify/memory/constitution.md`）当前为 v3.0.0，保持 Profile 统一契约、上下文资产非 Tool、状态外置和 9 模块审批基线；经用户批准，新增 007 三后端 Memory 范围及安全/兼容性门禁。**不允许 AI agent 未经用户批准自行修改 constitution**；原则与事实源冲突时必须停下讨论并同步。006 归档规格保留原验收范围，不能因宪法修订把新后端伪装成已交付。

实施纪律（都是文档点名 AI agent 容易出问题的地方）：
- **注释用中文**：代码注释（Javadoc/行内注释）与错误/审计消息一律简体中文，只写"为什么"；标识符、类名、方法名、`@author` 等保持英文。
- **版本锁定 Spec-Kit**：实施前锁定 Specify CLI 一个版本号，主体开发期间不升级，不引入社区 extension。
- **跨 user story 上下文别丢**：每个 user story 开始前重读 `spec.md` + `plan.md` + 最近代码。
- **US-2 的 Session 是内存版**，到 US-5 才升级成 SQLite 持久化（跨重启恢复）；`SandboxChecker` 在 US-2 是只校验 URL 的简化版，到 US-4 补齐文件/命令/域名完整版。别在早期 user story 就把后面的活干了。
- **MCP 集成先测连通性**：Java MCP Client 生态成熟度不如 Python，stdio transport 可能遇到进程启动失败、stdin/stdout 编码问题，US-4 动手前先用一个最简 MCP server 验证连通再展开。

主体开发完成、进入增量阶段（加 Channel、修 bug、加 Plugin Tool 等小颗粒度改动）后，切换到手动提示词直接改代码，不必每次都走完整 Spec-Kit 流程，但仍要遵守 constitution 里的原则。

## 参考

四份原始文档在 `docs/` 下，遇到本文件没覆盖到的细节，去对应文档查：功能范围和验收标准查 `DemandAnalysis.md`，模块职责和数据模型查 `TechnicalSolution.md`，任务拆解和踩坑对策查 `AiProgrammingGuide.md`，定位和竞品判断查 `IndustryResearch.md`。
