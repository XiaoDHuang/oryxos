# Implementation Plan: 插件化 Agent 目录——一个目录定义一个会自己跑的 Agent

**Branch**: `029-lesson29-agent-directory` | **Date**: 2026-09-09 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/012-agent-directory/spec.md`

## Summary

第 29 节（代码课）：给底座补上"定义一个 Agent、装上去、让它跑起来"的标准机制——`.oryxos/agents/<name>/` 一个自足目录（`AGENT.md` frontmatter = 运行配置、正文 = 任务指令；可选 `REFERENCE.md`/`skills/`/`scripts/` 按需取用）。一行底座不改：frontmatter 派生到同一 `Profile`、过同一套校验、进同一 `ProfileRegistry`，定时照旧由 `AgentScheduler` 注册。同时补运行时注册机制（`ProfileRegistry.register/remove/exists` + `AgentScheduler.registerProfile` 与句柄表），为 30 节 API 管理铺路。

## Technical Context

**Language/Version**: Java 21（JDK 21，虚拟线程）

**Primary Dependencies**: Spring Boot 3.x、Spring AI Alibaba（仅协议转换/Schema）、SnakeYAML（既有，frontmatter 解析复用）、Spring Data JPA + SQLite（既有，本节无新表）

**Storage**: 无新增存储。Agent 定义落文件系统 `.oryxos/agents/`；定时状态/历史复用 011 既有 `scheduled_tasks`/`task_executions`

**Testing**: JUnit 5 + AssertJ + Mockito（既有）；harness 六个测试类全部落 `oryxos-core/src/test`

**Target Platform**: Linux 主流发行版 / Windows 开发机，单可执行 fat JAR

**Project Type**: Maven 多模块单体（9 模块，本节不新增/不拆并）

**Performance Goals**: 启动扫描为一次性目录遍历，规模 ≤ 数十个 Agent 目录，无新增热路径；触发链路与既有 Profile 完全同径

**Constraints**: 不改 `AgentService`/`ReActLoop`/`PromptBuilder`/`Profile`/手写 profiles 路径；不形成第二套运行时模型；无新配置键/数据表/REST 端点/Profile 字段（`identity.promptFile` 是 §8.2 已定义、此前无消费者的既有字段，本节定义其语义，非新增）

**Scale/Scope**: 6 个 harness 测试类 + 5 个实现落点（AgentLoader、AgentDirectoryScanner、ProfileRegistry 改造、AgentScheduler 抽取、ContextLoader 加法）+ 1 个示例 Agent 目录

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 条款 | 判定 | 证据 |
|---|---|---|
| I 自实现 ReAct | ✅ | `ReActLoop`/`AgentService` 零改动，定时/人工触发同入口 |
| II Spring AI 仅协议转换 | ✅ | 不触碰 Provider/工具执行路径 |
| III Provider 显式映射 | ✅ | 校验复用全局 provider 名集合，不做容器扫描 |
| IV 配置即 Agent | ✅ | 正中条款：`AGENT.md` frontmatter 派生到同一 `Profile`，无第二套运行时模型、无跨 Agent 能力索引；Agent 目录资源由 ContextLoader 注入或经底座 Tool 按需取用，不注册进 ToolRegistry |
| V 审计 Day One | ✅ | 无新 Tool/LLM 路径，审计沿用既有落库 |
| VI 安全与数据边界 | ✅ | 无新 Tool；脚本执行走既有 shell 白名单（解释器+目录两道），"脚本=任意代码可绕 HTTP 域名白名单"的信任边界在 contracts 与 quickstart 如实记录；`${ENV_VAR}` 占位沿用 |
| VII 同步+虚拟线程 | ✅ | 无 Reactor/CompletableFuture/自建线程池；注册表并发用同步方法+保序 Map，不用并发框架新原语 |
| VIII 状态外置 | ✅ | 无新表；定时状态仍落 SQLite |
| 9 模块基线 | ✅ | 全部改动落 `oryxos-core` 既有包/类 + 示例目录；无模块变动 |
| 无新第三方依赖 | ✅ | frontmatter 解析复用既有 SnakeYAML；示例脚本为 Python 标准库、不进 Java 依赖 |

结论：无违规，无需 Complexity Tracking。Phase 1 设计后复核结论不变（见文末）。

## Project Structure

### Documentation (this feature)

```text
specs/012-agent-directory/
├── plan.md              # 本文件
├── research.md          # Phase 0：11 条设计决策
├── data-model.md        # Phase 1：AgentDefinition 与 frontmatter→Profile 映射
├── contracts/           # Phase 1：目录格式契约 + 运行时注册 Java 契约
│   ├── agent-directory-format.md
│   └── runtime-registration-api.md
├── quickstart.md        # Phase 1：harness 命令 + 真模型手工验证路径
├── samples/             # 示例 Agent 目录评审副本（同步到 .oryxos/agents/）
│   └── daily-reconcile/
├── checklists/
└── tasks.md             # /speckit-tasks 产出
```

### Source Code (repository root)

```text
oryxos-core/src/main/java/com/oryxos/core/
├── agent/                       # 新增包：Agent 目录机制（纯 POJO，装配在 config）
│   ├── AgentLoader.java         # 解析 AGENT.md → AgentDefinition；deriveProfile(dir) → Profile
│   ├── AgentDefinition.java     # 解析结果载体（frontmatter/正文/资源路径）
│   └── AgentDirectoryScanner.java  # 扫描 agents/：逐目录 load→派生→校验→注册→(可选)注册定时
├── profile/
│   ├── ProfileRegistry.java     # 改造点：可变保序 Map + register/remove/exists
│   ├── ProfileLoader.java       # 改造点：校验抽到 ProfileValidator，日志/跳过行为不变
│   └── ProfileValidator.java    # 新增 package-private：两条来源共用的一套校验
├── context/
│   └── ContextLoader.java       # 加法：identity.promptFile 现读注入、去 frontmatter
├── schedule/
│   └── AgentScheduler.java      # 改造点：registerAll 循环体抽出 registerProfile(Profile)
└── config/
    └── CoreEngineConfiguration.java  # 装配：scanner Bean（可选 scheduler）、保留既有 Bean

oryxos-core/src/test/java/com/oryxos/core/
├── agent/AgentLoaderTest.java
├── agent/DeriveProfileTest.java
├── agent/AgentScanRegisterTest.java
├── profile/ProfileRegistryRuntimeTest.java
├── schedule/AgentSchedulerRegisterTest.java
└── context/ProgressiveDisclosureTest.java

.oryxos/agents/daily-reconcile/  # 示例（gitignored，本地手工验证；评审副本在 specs/012/samples/）
├── AGENT.md
├── REFERENCE.md
├── skills/report-format.md
└── scripts/reconcile.py
```

**Structure Decision**: 全部实现落 `oryxos-core`（课件落位表第 29 行）；新增 `core.agent` 包承载目录机制（包内新增不属模块演进）；`ProfileRegistry`/`AgentScheduler`/`ContextLoader`/`ProfileLoader` 为课件点名的前序改造点，行为不回退。

## Complexity Tracking

无违规，留空。

## Phase 1 后 Constitution 复核

设计产物（research.md / data-model.md / contracts/ / quickstart.md）未引入新模块、新表、新端点、新 Profile 字段或新依赖；`identity.promptFile` 语义的定义属既有字段启用，frontmatter 键集与 Profile YAML 同构。复核结论：与 Phase 0 门禁判定一致，全部通过。
