<!--
Sync Impact Report
- Version change: 1.0.0 → 2.0.0
- Bump rationale: MAJOR — 将“核心阶段固定 9 模块”调整为“9 模块是当前基线、经显式审批可演进”，
  属于不兼容治理规则变更；同时统一 Agent 配置、上下文资产与运行时 Profile 的边界。
- Modified principles:
  - I. 自实现 ReAct loop → I. 自实现 ReAct 循环
  - II. Spring AI 只用一半 → II. Spring AI 仅做协议转换与 Schema 生成
  - 新增独立原则 III. Provider 显式映射
  - IV. Tool 统一抽象 → IV. 配置即 Agent，上下文资产不是 Tool
  - VII. SQLite 持久化，审计 day one 落库 → V. 审计 Day One 落库
  - VIII. 安全合规基线 → VI. 安全与数据边界是地基
  - III/V. 同步阻塞与 Spring MVC → VII. 同步执行、虚拟线程与 Spring MVC
  - 持久化与无状态约束 → VIII. 运行实例无状态，状态外置
- Added sections: 技术栈与架构约束；开发流程与质量门禁；Governance（重写并明确事实源边界）
- Removed sections: 无；旧“项目级底线”内容归并到原则和架构约束
- Templates checked:
  - .specify/templates/plan-template.md ✅ 无需修改（Constitution Check 为动态占位）
  - .specify/templates/spec-template.md ✅ 无需修改
  - .specify/templates/tasks-template.md ✅ 已同步核心特性测试与完整门禁要求
- Runtime guidance synchronized:
  - AGENTS.md ✅
  - CLAUDE.md ✅（软链指向 AGENTS.md）
  - docs/TechnicalSolution.md ✅
  - docs/AiProgrammingGuide.md ✅
- Feature artifacts synchronized:
  - specs/003-cli-session/plan.md ✅ Constitution Check 更新为 v2.0.0 原则
  - specs/003-cli-session/tasks.md ✅ 完整门禁与补救阶段待本轮实现收口
- Follow-up TODOs: 无
-->

# OryxOS Constitution

OryxOS 是用 Java 实现、面向企业私有部署的 Agent OS。当前交付是单体运行时内核，远期可按真实
需求演进到分布式底座。本宪法定义不可绕过的工程与架构门禁；产品范围、数据模型与阶段边界以
`docs/` 四份事实源文档为依据，二者发生冲突时必须停止实施，由用户决议并在同一变更中同步。

## Core Principles

### I. 自实现 ReAct 循环 (NON-NEGOTIABLE)

`ReActLoop` MUST 由 OryxOS 自己实现并掌握迭代控制、消息累积、工具调度入口、终止条件与上下文
裁剪。MUST NOT 使用 Spring AI 的 Agent 抽象，也不得把循环控制权委托给外部编排框架。

**Rationale**: ReAct 运行机制是 OryxOS 的核心控制面；让出循环会丢失可控性并增加重复执行风险。

### II. Spring AI 仅做协议转换与 Schema 生成 (NON-NEGOTIABLE)

Spring AI MUST 只用于 Provider 协议转换和 `@Tool` JSON Schema 生成。其自动 tool 执行 MUST
显式禁用；Tool 的实际调度与执行 MUST 由 `ReActLoop` + `ToolExecutor` 独占控制。会因缺少凭证
阻断无关命令启动的 eager Provider 自动装配 MUST 禁用或隔离。

**Rationale**: OryxOS 借用协议管道，但不把执行权交给框架。

### III. Provider 显式映射

多 Provider 并存时 MUST 维护显式的 `provider name → ChatModel` 映射表。MUST NOT 依赖扫描
Spring 容器中的 `ChatModel` Bean 类型或不稳定的 Bean name 推断 Provider。

**Rationale**: 相同实现类型无法表达业务 Provider 名，显式映射才能保证路由可预测。

### IV. 配置即 Agent，上下文资产不是 Tool

业务 Agent MUST 通过声明式配置产生，MUST NOT 为具体客服、运维等 Agent 新建 Java 子类。
`Profile` 是当前运行时的统一契约；Profile YAML 是核心阶段的配置入口。`SKILL.md`、Bootstrap
文件以及未来可能引入的 `AGENT.md`/Agent 目录都属于可审计的作者资产，由 `ContextLoader` 注入
prompt 或由显式 Loader 派生为 `Profile`，MUST NOT 注册进 `ToolRegistry`。若引入 Agent 目录，
其 frontmatter MUST 派生到同一 `Profile`，不得形成第二套运行时模型或跨 Agent 全局能力索引。
内置、MCP、Plugin Tool MUST 统一包装为 `OryxTool`。

**Rationale**: Profile 统一运行时语义；上下文告诉模型“怎么做”，Tool 才负责“执行动作”。

### V. 审计 Day One 落库 (NON-NEGOTIABLE)

`tool_invocations` 与 `llm_calls` MUST 从核心阶段起写入 SQLite，MUST NOT 只写日志。审计写入
失败不得静默；是否阻断主流程由对应特性契约明确，但失败必须可观测。

**Rationale**: 可审计是企业底座的差异化能力，事后从日志反解析不可接受。

### VI. 安全与数据边界是地基 (NON-NEGOTIABLE)

任何 File/Shell/HTTP/MCP/Plugin Tool 在注册为可执行能力或对外发布前，调用链 MUST 接入
`SandboxChecker` 白名单校验：文件路径、Shell 首 token、HTTP 域名分别校验。早期 feature 在尚未
注册可执行 Tool 时 MAY 只保留 Sandbox 契约接入位，但不得把未校验的 Tool 默认开放。MUST NOT
使用 `SecurityManager`。凭证 MUST 通过环境变量或企业密钥体系注入，MUST NOT 明文进入代码、
配置、日志或提交历史。OryxOS MUST NOT 默认上报遥测或主动外发企业数据。

**Rationale**: 私有部署、最小权限和数据不出域是企业采用 OryxOS 的前提。

### VII. 同步执行、虚拟线程与 Spring MVC

核心阶段 MUST 使用同步阻塞模型和 Java 21 虚拟线程。MUST NOT 引入 Reactor、WebFlux、
`CompletableFuture` 并发编排、自建固定线程池或 SSE 流式链路。HTTP 层 MUST 使用 Spring MVC。

**Rationale**: 虚拟线程已经覆盖当前并发需求，同步代码更易审计、调试和维护。

### VIII. 运行实例无状态，状态外置

会话、记忆与审计状态 MUST 外置到 SQLite 或受控文件；长期记忆使用 `MEMORY.md`。SQLite 表结构
变更 MUST 使用手工脚本或显式迁移工具，MUST NOT 依赖 `hibernate.ddl-auto=update`。运行实例不得
把唯一业务状态只保存在进程内。

**Rationale**: 状态外置保证重启恢复，并为未来横向扩展保留演进路径。

## 技术栈与架构约束

- 当前基线是 JDK 21 + Spring Boot 3.x 单体、Maven 9 模块、单可执行 fat JAR。核心阶段默认
  MUST 保持现有 9 模块边界。
- 模块结构 MAY 演进，但新增、删除、改名或移动职责前 MUST 在对应 feature 的 plan 中说明必要性，
  获得用户显式批准，并在实现前同步 `AGENTS.md`、`docs/TechnicalSolution.md` 与受影响构建配置。
  未完成这些步骤时不得以“未来扩展”为由擅自拆模块。
- `oryxos-core` 放跨模块契约与运行时引擎；下游模块实现契约，MUST NOT 形成 Maven 循环依赖。
- 当前核心阶段只做运行时，不做可视化 workflow、多 Agent 显式协作、复杂任务分解、多租户、SSO、
  RBAC、限流或完整治理平台。MCP stdio 是核心 Tool 开放标准；A2A 与分布式协调属于扩展阶段候选。
- 持久化使用 SQLite + Spring Data JPA；日志使用 Logback/SLF4J，生产输出结构化 JSON。
- Tool 体系当前保持在单一 `oryxos-tool` 模块；若未来独立 Sandbox 等能力，必须走上述模块演进流程。

## 开发流程与质量门禁

- 主体开发 MUST 走 Spec-Kit：constitution → specify → clarify（需要时）→ plan → tasks → analyze →
  implement → verify → analyze。一次只推进一个 feature，前一 feature 必须有干净稳定提交才能切换。
- 每个核心特性 MUST 有可独立运行的自动化验收；不得删除断言、添加 `@Disabled` 或放宽阈值换绿。
- 本地迭代 MAY 使用 `-Ddependency-check.skip=true` 加速，但只能称为“快速门禁”。合并、封板或进入
  下一 feature 前 MUST 执行不跳过任何插件的 `mvn verify`，确保 Spotless、P3C、Checkstyle、
  SpotBugs/Find Security Bugs 与 OWASP Dependency-Check 全绿。
- Spec、Plan、Tasks 与实现 MUST 同步；补救性代码必须在 tasks 中留下可追踪任务与验收证据。
- 敏感配置使用 `${ENV_VAR}` 占位；缺失或非法配置必须给出清晰错误，不得泄漏密钥。

## Governance

- `docs/IndustryResearch.md`、`docs/DemandAnalysis.md`、`docs/TechnicalSolution.md`、
  `docs/AiProgrammingGuide.md` 是产品与技术事实源；本宪法是实施治理门禁。两者冲突时 MUST 停止，
  由用户决议后同步修改，不得选择性忽略。
- 宪法修订 MUST 有用户显式批准，并同步受影响模板、feature artifacts、`AGENTS.md` 与事实源文档。
  `CLAUDE.md` 是指向 `AGENTS.md` 的软链，不单独维护。
- 版本策略：MAJOR = 删除或不兼容地重定义原则/治理规则；MINOR = 新增原则、章节或兼容性扩充；
  PATCH = 非语义澄清与笔误修正。
- 每个 feature 的 plan 和最终评审 MUST 逐条核对当前宪法；复杂度例外必须记录在 Complexity Tracking。
- AI Agent MUST NOT 未经用户批准自行修改本宪法。

**Version**: 2.0.0 | **Ratified**: 2026-07-20 | **Last Amended**: 2026-08-24
