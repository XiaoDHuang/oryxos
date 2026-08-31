<!--
Sync Impact Report
- Version change: 2.0.0 → 3.0.0
- Bump rationale: MAJOR — 经用户批准，将长期记忆仅限 MEMORY.md 的原则重新定义为
  Markdown 默认、SQLite 与自托管 Mem0 显式可选；该核心范围扩张由 feature 007 承接。
- Modified principles:
  - VI. 安全与数据边界是地基（补充外部记忆服务及其下游数据路径门禁）
  - VIII. 运行实例无状态，状态外置（允许三后端，不改变 Session/审计持久化）
- Added sections: Memory 后端范围与兼容性
- Removed sections: 无
- Templates checked:
  - .specify/templates/plan-template.md ✅ 补充 Memory 配置、依赖与协议核验门禁
  - .specify/templates/spec-template.md ✅ 无需修改（已有需求、边界与可测验收章节）
  - .specify/templates/tasks-template.md ✅ 补充真实适配器契约及后端差异验收
- Installed Spec Kit commands: .agents/skills/speckit-*/SKILL.md ✅ 已检查，无需修改
- Runtime guidance synchronized:
  - AGENTS.md ✅
  - CLAUDE.md ✅（软链指向 AGENTS.md）
  - README.md ✅
  - docs/IndustryResearch.md ✅
  - docs/DemandAnalysis.md ✅
  - docs/TechnicalSolution.md ✅
  - docs/AiProgrammingGuide.md ✅
  - docs/class/第21节：Memory 原理解析、业界方案与 OryxOS 设计评审.md ✅ 补充新决议
  - docs/class/第22节：Memory 实现与代码讲解.md ✅ 区分 006 历史基线与 007 续篇
  - .agents/skills/oryxos-lesson-dev/SKILL.md ✅ 修正 Memory 落位与续篇路由
- Feature scope record: docs/decisions/007-memory-backends-scope.md ✅
- Historical artifacts: specs/006-memory/ 保留原批准范围及验收证据，不追溯改写
- Follow-up: 007 specify/clarify/plan/tasks 尚未生成；Mem0 协议、数据路径和可见性须在 plan 核验。
  无宪法占位符延期；范围批准不等于实现或发布验收通过。
  课程技能 quick_validate.py 缺少 PyYAML 未执行；已人工/静态核对正文路由、链接及未变的 frontmatter。
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

外部记忆后端 MUST 显式启用，仅允许企业自托管部署；每次网络 I/O 前 MUST 校验允许的目标，
缺少安全接线 MUST 拒绝调用。数据边界核验 MUST 包括记忆服务、模型、embedding 与存储下游，
不得把“自托管入口”当作“全链路不出域”的证据。未完成核验不得启用 Mem0。

**Rationale**: 私有部署、最小权限和数据不出域是企业采用 OryxOS 的前提。

### VII. 同步执行、虚拟线程与 Spring MVC

核心阶段 MUST 使用同步阻塞模型和 Java 21 虚拟线程。MUST NOT 引入 Reactor、WebFlux、
`CompletableFuture` 并发编排、自建固定线程池或 SSE 流式链路。HTTP 层 MUST 使用 Spring MVC。

**Rationale**: 虚拟线程已经覆盖当前并发需求，同步代码更易审计、调试和维护。

### VIII. 运行实例无状态，状态外置

会话与审计状态 MUST 外置到 SQLite。长期记忆 MUST 使用显式选定的后端：默认受控文件
`MEMORY.md`，或 SQLite `memory_entries`，或通过数据边界门禁的自托管 Mem0。SQLite 表结构
创建及演进 MUST 使用手工脚本或显式迁移工具，MUST NOT 依赖 `hibernate.ddl-auto=update`。
运行实例不得把唯一业务状态只保存在进程内。

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

### Memory 后端范围与兼容性

- 006 是已验收的文件式基线；三后端范围由 007 承接。MUST 保留 core 中 `MemoryService` /
  `MemoryScope` 的公共签名与引擎依赖方向；存储抽象和实现归 memory，表实体与仓储归 storage。
- `memory.backend` MUST 在启动时唯一选定 `markdown`（默认）、`sqlite` 或 `mem0`。非法选择
  MUST 明确失败；MUST NOT 静默降级、隐式迁移、删除旧后端数据或默认连接 Mem0。
- 核心记忆 MUST 完整注入；归档检索 MUST 排除核心记忆。Markdown/SQLite 保留关键词检索，
  Mem0 MAY 使用已核验的语义检索。归档窗口 MUST 分别定义：Markdown 最近 4000 Java char，
  SQLite 最近 100 条，Mem0 在 plan 锁定分页及窗口规则；裁剪不得删除持久化数据。
- OryxOS MUST NOT 自动触发对话提炼或记忆压缩。Mem0 写入时的后端推理 MAY 仅在显式保存、
  数据路径与审计证据获准后启用；不得由后端推理改写核心记忆的完整保存契约。
- 写入成功后下一轮 MUST 可读；后端若异步处理，plan MUST 明确有界等待与失败语义，不得用
  “最终一致”静默放宽该契约。超时/拒绝/部分失败 MUST 可观测，不得伪装为空记忆或保存成功。
- 共同契约测试 MUST 经过真实适配器，外部服务可用传输层替身；同时 MUST 有各后端差异测试。
  Tool 审计继续落 `tool_invocations`；Mem0 内部模型调用不得冒充已经记录到 OryxOS `llm_calls`，
  其审计来源和关联方式 MUST 在 plan 给出并验证。

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

**Version**: 3.0.0 | **Ratified**: 2026-07-20 | **Last Amended**: 2026-08-30
