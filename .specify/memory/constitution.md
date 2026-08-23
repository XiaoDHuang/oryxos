<!--
Sync Impact Report
- Version change: (template, unversioned) → 1.0.0(2026-08-22 初填;2026-08-23 按课件《Harness 设计》《Spec-Kit 执行指导》的「八原则」表述更正,补入原则 VIII)
- Modified principles: 模板占位 → 正式原则 I~VIII(内容严格取自 AGENTS.md 与 docs/ 四份文档,未新造)
- Added sections: Core Principles(I~VIII)、项目级底线、开发流程与质量门禁、Governance
- Removed sections: 无(仅移除模板占位与示例注释)
- Templates requiring updates:
  - .specify/templates/plan-template.md ⚠ 无需修改(Constitution Check 小节为占位,由各 plan 实例化时对齐本文件)
  - .specify/templates/spec-template.md ✅ 无需修改(无强制小节冲突)
  - .specify/templates/tasks-template.md ✅ 无需修改(测试任务由本宪法「质量门禁」原则要求,生成时体现)
- Follow-up TODOs: 无
-->

# OryxOS Constitution

## Core Principles

### I. 自实现 ReAct loop

OryxOS MUST 自己实现 ReAct 循环, MUST NOT 使用 Spring AI 的 Agent 抽象。
核心循环(迭代控制、消息累积、工具调度入口)的控制权必须完全在 OryxOS 手里,
这是本项目的存在意义;委托给框架等于让出差异化根基。

### II. Spring AI 只用一半

Spring AI MUST 只用于两件事:Provider 协议转换、`@Tool` 的 JSON Schema 生成。
其自动 tool 执行机制 MUST 显式禁用;Tool 的实际调度与执行 MUST 完全由
`ReActLoop` + `ToolExecutor` 控制。两套执行并存会导致工具被调两次且绕过沙箱,
属最高危回归点,必须有测试永久钉死。

### III. 同步阻塞 + 虚拟线程

执行模型 MUST 为同步阻塞 + Java 21 virtual thread。
核心阶段 MUST NOT 引入响应式编程(Reactor/WebFlux)、流式 SSE、`CompletableFuture`
并发编排或自建线程池。

### IV. Tool 统一抽象

Tool 注册 MUST 走 `@Tool` 注解 + `OryxTool` 抽象层;内置、MCP、Plugin 三种来源
MUST 统一包装成 `OryxTool`,使 ReAct loop 不感知 Tool 来源。

### V. HTTP 层 Spring MVC

Web 层 MUST 用 Spring MVC + 虚拟线程, MUST NOT 引入 WebFlux。

### VI. Sandbox 白名单,禁用 SecurityManager

Tool 执行隔离 MUST 用 Path/Pattern 白名单做应用层校验
(`file.allowed_paths` / `shell.allowed_commands` / `http.allowed_domains`)。
MUST NOT 使用 Java SecurityManager(JDK 17 起废弃、JDK 21 不可用,与项目 JDK 基线直接冲突)。

### VII. SQLite 持久化,审计 day one 落库

持久化 MUST 用 SQLite + Spring Data JPA;长期记忆走 `MEMORY.md` 文件。
`tool_invocations` 与 `llm_calls` 两张审计表 MUST 在核心阶段就写入落库
(不必等查询接口,也 MUST NOT 只写日志不落库)——「可审计」是差异化卖点,地基 day one 立起来。
表结构演进 MUST 靠手工维护的脚本(SQLite ALTER TABLE 能力弱),
MUST NOT 依赖 `hibernate.ddl-auto=update` 做迁移。

### VIII. 安全合规基线

敏感配置(API key、DB 密码、Tool 凭证)MUST 以 `${ENV_VAR}` 占位从环境变量注入;
代码、配置文件、日志中 MUST NOT 出现明文密钥。Tool 调用 MUST 过白名单校验。
OryxOS MUST NOT 主动外发任何数据——任何新功能设计 MUST NOT 引入默认上报或遥测。
本条是硬约束,也是 H4 全局不变量第③条(凭证只走环境变量,grep 无明文 key)的原则级出处。

## 项目级底线

- 技术栈:JDK 21 + Spring Boot 3.x 单体,fat JAR 单二进制部署;
  Maven 固定 9 模块(oryxos-core/-provider/-memory/-tool/-web/-channel-cli/-storage/-cli/-boot),
  MUST NOT 增删或合并模块。
- Agent 是「配置」出来的(Profile YAML),MUST NOT 为具体业务 Agent 写 Java 类/新模块。
- OryxOS 做运行时,不做编排:MUST NOT 造可视化 workflow 编排、复杂任务分解、多 Agent 显式协作引擎。
- 五大核心能力(Provider / ReAct / Memory / Tool / Web)优先,支撑模块次之。
- Plugin Tool 三档接入,主推零代码 `SKILL.md` + MCP;MCP Client 核心阶段只做 stdio transport。
- 跑通优先于完美:核心阶段 MUST NOT 做多租户、SSO、完整审计查询、Tool Policy、
  Web 仪表板、认证/RBAC、SSE/WebSocket、限流等治理层能力。
- 质量门禁:代码 MUST 过 Google 格式(Spotless)+ 阿里 P3C(PMD)+ Checkstyle +
  SpotBugs/FindSecBugs + OWASP Dependency-Check,方可合并;判定命令为 `mvn clean verify` 全绿。

## 开发流程与质量门禁

- 主体开发走 Spec-Kit(constitution → specify → clarify → plan → tasks → implement),
  按 user story 组织;每个 user story 完成后 MUST 跑一次 `/speckit-analyze` 检查漂移。
- 每个核心功能模块 MUST 至少有一个端到端测试用例覆盖;五个验收 Demo 是核心功能发布的硬条件。
- 测试纪律:MUST NOT 删断言、加 `@Disabled`、放宽阈值让测试变绿;实现错修实现。
- 完成判定:`mvn clean verify` 全绿,且 Spec-Kit 产物保留在仓库作为长期参考。

## Governance

- 本宪法优先级高于其他实践与个体偏好;与 `docs/` 四份文档冲突时,以四份文档为准并回修本文件与 AGENTS.md。
- AI agent MUST NOT 自行修改本文件;发现原则不对时 MUST 停下与用户讨论。
- 修订程序:书面说明理由 → 用户批准 → 更新本文件(版本号递增)→ 同步 AGENTS.md 与受影响模板。
- 版本规则(语义化):MAJOR=原则移除或重定义等不兼容变更;MINOR=新增原则/小节或实质扩充;PATCH=措辞澄清。
- 合规审查:每次 `/speckit-plan` 的 Constitution Check、每个 PR 评审 MUST 对照本文件逐条核查。

**Version**: 1.0.0 | **Ratified**: 2026-07-20 | **Last Amended**: 2026-08-23
