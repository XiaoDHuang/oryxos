# Implementation Plan: 定时任务（第三种触发源）

**Branch**: `025-lesson25-scheduler` | **Date**: 2026-09-05 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/009-agent-scheduler/spec.md`

## Summary

为 AgentService 补第三种触发源：Profile 的 `schedules` 字段落成强类型 `ScheduleConfig`（id/cron/zone/message），`AgentScheduler` 在常驻模式（serve/gateway）启动时把所有 Profile 的合法规则动态注册进 Spring 的 `ThreadPoolTaskScheduler`（`CronTrigger` 携带 cron+时区）；到点触发按任务 id 进程内 `ReentrantLock` 防重叠，以固定三元组 ("scheduler","scheduler",profileName) 复用 Session，走与人推完全相同的 `AgentService.process` 入口，失败只记日志且 finally 放锁。chat 模式不注册（用户决议，对齐技术方案 §8.6）。无新表、无新 Maven 依赖、9 模块不变。

## Technical Context

**Language/Version**: JDK 21（既有基线）

**Primary Dependencies**: Spring Boot 3.5.16（锁定 BOM）→ spring-context 6.2.19：`ThreadPoolTaskScheduler` / `TaskScheduler.schedule(Runnable, Trigger)` / `CronTrigger(String, ZoneId)` 均已 javap 核实存在（见 research.md）；`ThreadPoolTaskScheduler` Bean 由 spring-boot-autoconfigure 3.5.16 `TaskSchedulingAutoConfiguration` 在无缺省时自动提供，已核实。无新增第三方依赖。

**Storage**: 无新表。Session 复用既有 `sessions` 表与三元组幂等；审计复用既有 `llm_calls`/`tool_invocations` 链路。

**Testing**: JUnit 5 + Mockito + AssertJ（spring-boot-starter-test）；`AgentSchedulerTest` 承载课件 harness 四个回归点；单测默认跑；实现完成的定义是 `mvn clean verify` 全绿。

**Target Platform**: Linux 服务器 / Windows 开发机（既有基线）

**Project Type**: Maven 多模块单体（9 模块不变）

**Performance Goals**: 不新增性能目标；调度开销可忽略（单条注册、触发走既有处理链路）

**Constraints**: 避开 P3C/ASM 解析不了的 Java 18+ 语法形态（静态检查是构建门禁）；核心引擎类保持纯 POJO、由 `CoreEngineConfiguration` 装配的既有模式；注释/日志简体中文、标识符英文。

**Scale/Scope**: 单实例进程内调度；规则数量为 Profile 声明量级（个位数~数十条）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 条款 | 判定 | 依据 |
|---|---|---|
| I. 自实现 ReAct 循环 | ✅ 不受影响 | 本特性不触碰 ReActLoop，只新增一个调用 `AgentService.process` 的触发源 |
| II. Spring AI 仅协议转换 | ✅ 不受影响 | 不引入 Spring AI 新用法 |
| III. Provider 显式映射 | ✅ 不受影响 | 不涉及 Provider |
| IV. 配置即 Agent | ✅ 正中原则 | 定时规则是 Profile 声明式配置（schedules 字段），不为任何场景新建 Agent Java 子类；ScheduleConfig 是配置记录，非 Tool、不进 ToolRegistry |
| V. 审计 Day One | ✅ 复用既有链路 | 定时触发的 LLM/工具调用经既有 `AgentService.process` 路径落 `llm_calls`/`tool_invocations`，不另开一套、不新增表 |
| VI. 安全与数据边界 | ✅ 不新增涉外 IO | 调度器只调进程内 AgentService；无凭证、无外发、无 SecurityManager |
| VII. 同步执行/虚拟线程 | ✅ 说明见下 | `ThreadPoolTaskScheduler` 是技术方案 §8.5 指定的 Spring 托管调度设施，非业务自建线程池；任务体 runOnce 同步阻塞执行，不引入 Reactor/CompletableFuture |
| VIII. 状态外置 | ✅ | Session/审计外置 SQLite 复用既有；锁表是进程内易失控制状态，非业务状态 |
| 9 模块基线 | ✅ 不变 | 落位 oryxos-core（AgentScheduler/ScheduleConfig）+ oryxos-cli（启动信号传参），无模块增删改 |
| SQLite 手工迁移 | ✅ 无表变更 | — |

**Gate 结论**：无违规，无需 Complexity Tracking。Phase 1 设计后复核结论不变。

## Project Structure

### Documentation (this feature)

```text
specs/009-agent-scheduler/
├── plan.md              # 本文件
├── research.md          # Phase 0：API 核实与决策
├── data-model.md        # Phase 1：ScheduleConfig 与 Profile 变化
├── quickstart.md        # Phase 1：验证指南
├── contracts/
│   └── schedule-config-yaml.md  # schedules 配置块 + 启用信号契约
└── tasks.md             # /speckit-tasks 产出
```

### Source Code (repository root)

```text
oryxos-core/src/main/java/com/oryxos/core/
├── profile/
│   ├── Profile.java              # 修改：schedules 由 List<Map<String,Object>> 强类型化为 List<ScheduleConfig>
│   ├── ScheduleConfig.java       # 新增：定时规则记录（id/cron/zone/message）
│   └── ProfileRegistry.java      # 修改：新增 all() 枚举（课件骨架 profileRegistry.all() 的直接要求）
├── schedule/
│   └── AgentScheduler.java       # 新增：registerAll + runOnce + 按任务 id 的 ReentrantLock 表 + lockFor（测试用）
└── config/
    └── CoreEngineConfiguration.java  # 修改：agentScheduler @Bean 按 @ConditionalOnProperty(oryxos.scheduler.enabled=true) 条件装配并 registerAll()

oryxos-core/src/test/java/com/oryxos/core/
├── schedule/
│   └── AgentSchedulerTest.java   # 新增：课件 harness 四回归点 + FR-008 非法规则处置
├── profile/
│   └── ProfileLoaderTest.java    # 修改：schedules 夹具/断言适配强类型（name→id 四要素）
└── config/
    └── CoreEngineConfigurationTest.java  # 修改：补启用信号开/关两条装配回归

oryxos-cli/src/main/java/com/oryxos/cli/
├── ServeCommand.java             # 修改：SpringRuntime.start 追加 --oryxos.scheduler.enabled=true
└── GatewayCommand.java           # 修改：同上（ChatCommand 不传，chat 不注册）
```

**Structure Decision**: 单体内既有模块落位。`ScheduleConfig` 放 `com.oryxos.core.profile`（与 Profile 同包，作为其配置块类型），`AgentScheduler` 放新包 `com.oryxos.core.schedule`，依赖方向 schedule → profile 单向，无包循环。CLI 只传启动信号，不感知调度器类型。

## Complexity Tracking

无（Constitution Check 全过，无需例外记录）。
