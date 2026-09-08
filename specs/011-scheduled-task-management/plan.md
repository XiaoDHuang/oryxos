# Implementation Plan: 持久化定时任务与运行管理

**Branch**: `028-lesson28-scheduler-management` | **Date**: 2026-09-07 | **Spec**: [spec.md](spec.md)
**Input**: `specs/011-scheduled-task-management/spec.md`
**Status**: 设计完成，等待 tasks 审阅；没有实现或测试通过声明。

## Summary

在既有九模块内完成 Profile 定义→调度执行→任务状态/历史→管理操作闭环，保留统一 AgentService 入口。用户已批准 D28-01–04；009/010 为历史基线，011 独立验收。三层验证分别为默认无 key 整机、真实模型通知、独立进程重启。

## Technical Context

**Language/Version**: Java 21（本机 21.0.11）、Vue 3。
**Primary Dependencies**: 锁定 Boot 3.5.16、Spring 6.2.19、Spring AI 1.1.8、Hibernate 6.6.53.Final、SQLite JDBC 3.53.2.1；既有 Vite 7.3.6。未新增或升级依赖。通用课件提到 Alibaba；仓库实际使用 Spring AI BOM/OpenAI 协议，沿用已验收配置，不引入另一套 BOM。
**Storage**: 同一工作区 SQLite，两张新增表；默认长期记忆仍 Markdown；幂等追加 `oryxos-storage/src/main/resources/db/schema.sql`，`ddl-auto=none`。
**Testing**: JUnit/Mockito、真实 JPA/临时 SQLite、SpringBootTest、独立子进程、真实 Provider+测试 webhook、前端构建及浏览器。主模型编写实现和回归。
**Target Platform**: Linux 服务部署，当前 Windows/Git Bash 开发机；重启仅限测试子进程。
**Project Type**: 九模块单体 fat JAR，开发保留独立 Vite。
**Performance Goals**: 单次执行 60 秒截止；不同 Profile 不被单条阻塞任务占住触发线程；既有十 Agent/百 Session 性能目标不变，不把四小时压测归入本节完成证明。
**Constraints**: 无新公开配置键，无新 Memory/AgentService/SessionManager 端口签名，无新模块；无 Reactor/CompletableFuture/业务固定线程池；同步引擎跑在 Spring 管理虚拟线程上。
**Scale/Scope**: 新增两表、四 REST 操作和一个页面；两条启动模式仍只有 serve/gateway 注册，chat 不触发。

## Constitution Check

| 条款 | 设计证据 | 结果 |
|---|---|---|
| I 自有 ReAct | 保留循环与工具控制权，只附内部结果元数据/中断检查 | PASS |
| II 禁框架执行 | Provider 的 internalToolExecutionEnabled(false) 不变，三轮两工具对账 | PASS |
| III 显式 Provider | 复用显式 mock/deepseek，不扫 ChatModel 猜身份 | PASS |
| IV 配置与上下文 | 定义仍为 Profile.schedules，无 Skill 调度解析器 | PASS |
| V 审计落库 | 原调用审计继续执行；新任务开始/终态短事务；失败可观测 | PASS |
| VI 数据边界 | 所有通知/天气仍过 Sandbox，凭证仅环境注入，独立测试材料 | PASS |
| VII 同步/虚拟线程 | Spring SimpleAsyncTaskExecutor 虚拟 worker；时钟只派发和发取消 | PASS |
| VIII 状态外置 | 两表保存状态/历史，进行中先落库、重启标未知，不重放 | PASS |
| 九模块/无循环依赖 | core 定义端口，storage 实现，web 只依赖 core，boot 组合 | PASS |
| Memory 兼容 | 不改后端选择、内容、注入窗口及 Mem0 生产协议 | PASS |
| 流程/门禁 | Spec Kit 0.14.2 不升级；默认/IT/完整 verify/复审分别记录 | PASS |

研究前和设计后均未发现需要修改宪法原则的例外；宪法仍 v3.0.0。本表证明设计符合规则，不代表运行门禁已通过。

## Project Structure

### Documentation (this feature)

```text
specs/011-scheduled-task-management/
  spec.md
  plan.md
  research.md
  data-model.md
  contracts/schedules.md
  quickstart.md
  tasks.md
  checklists/requirements.md
```

### Source Code (repository root)

- `oryxos-core/src/main/java/com/oryxos/core/schedule/`：既有 AgentScheduler；新增 ScheduledTaskStore、ScheduledTaskView、TaskExecutionView。内部执行状态容器为私有嵌套类型，不新增通用调度框架。
- `oryxos-core/src/main/java/com/oryxos/core/config/CoreEngineConfiguration.java`：实际装配与生命周期；既有 Bean 方法改造是课件 AgentScheduler 改造的接线部分。
- `oryxos-core/src/main/java/com/oryxos/core/react/ReActLoop.java`：工具结果内部 metadata、轮次中断守卫，不改公共 run 签名。
- `oryxos-storage/src/main/java/com/oryxos/storage/schedule/`：ScheduledTask、TaskExecution、两个同名 Repository、JpaScheduledTaskStore。
- `oryxos-storage/src/main/resources/db/schema.sql`：仅新增表/索引。
- `oryxos-web/src/main/java/com/oryxos/web/api/ScheduleApiController.java`：四端点及嵌套 DTO，错误复用 OryxException/ErrorCode。
- `oryxos-web/src/main/frontend/src/views/SchedulesView.vue`：列表、历史、执行/启停；配合 api.js、main.js、App.vue、assets/main.css。
- `oryxos-boot/src/test/java/com/oryxos/boot/`：ScheduledTaskE2ETest、SchedulerFlowIT、RestartRecoveryIT、MultiAgentIsolationTest、SchedulerStabilityTest、包私有 SchedulerFlowFixture。
- 测试按模块放到相同包；前端生产产物仍在 `oryxos-web/src/main/resources/static/admin/`。

**Structure Decision**: 保持 core←storage / core←web；不把 JPA 放 core、不让 core 引入下游 web 错误类型。

## Phase 0: Research

[research.md](research.md) 记录实际 dependency:tree、javap、现有源代码核实。第三方 API 已在本地依赖核实；未新增库。课件错误计数的修正有 D28-04 明确批准。业务未决项采用 spec 的显式默认值，逐项进入 data-model/contract 测试。

## Phase 1: Design

### 1. 登记、启动与重启

- AgentScheduler 保留 registerAll；校验 cron/zone/四要素/全局 id，保存当前可运行规则的不可变 catalog。catalog 只作进程内索引，唯一业务状态由 Store 保存。
- 初始化顺序：schema 初始化完成→Store 可用→恢复遗留 running→登记当前规则→安装 cron。将注册从 Bean 工厂内即时执行移到 Spring 上下文完成初始化后的回调（现有配置类的包私有 Bean），避免新 JPA 写入早于 schema。
- 重复 registerAll 先取消自己的旧 futures，再校验/注册；不得重复安装。重启补救只执行一次，不在每次 registerAll 把当前在途任务误标未知。
- 相同 id/相同 Profile 更新 cron/zone/message 快照，保留 enabled/counter/history；同 id 换 Profile 拒绝。消失/非法规则保留 DB 行，但 catalog 不含它：只读可查，执行/启用拒绝；next_run_at 清空。
- next_run_at 是有效已启用规则未来的 cron 候选，不保证一定运行（可能因占用跳过）。禁用/不可运行为 null；立即执行不重置 cron 节奏，启用与重启计算未来候选，不追赶停机点。
- 启停不直接修改 cron/message；持久化开关是执行准入条件。开始与启停以同一任务的短准入互斥序列化（只涵盖开关检查/begin事务，不持有至引擎结束），先开始的任务继续，先停用则自动触发跳过。该准入互斥独立于worker全程持有的执行锁，因此停用无需等待正在执行的任务结束。

### 2. 执行、隔离与截止

- ThreadPoolTaskScheduler 只执行短回调：派发虚拟 worker、更新候选时间、watchdog；不直接等待 AgentService。
- 现有 CoreEngineConfiguration 增包私有 Spring Bean `SimpleAsyncTaskExecutor`，virtualThreads=true，destroyMethod=close，有界关闭等待并取消残余线程；不自建固定池。
- worker 使用按 taskId 和 profileName 的 tryLock，固定先任务后 Profile；当前 worker 获得并释放 ReentrantLock，拿不到立即结束。runNow 的等待发生在外层请求线程，不占 cron 线程；通过私有结果载体与 CountDownLatch 获取结果，不用 CompletableFuture。
- 两把锁在 worker finally 才释放。覆盖同 Profile 两规则共享 scheduler Session 的丢更新风险；不宣称一般 HTTP 同 Session 并发已被本节改造。
- 拿锁、检查 enabled/catalog、获取 Session 后开始 Store 事务；begin 提交后再调用模型/工具；begin 失败绝不执行副作用。
- 60 秒起点为实际准入时刻；watchdog 标记取消、中断 worker、终结本次记录并唤醒请求方。计数只在 begin 增加一次。worker 完成与 watchdog 以条件终态写入竞争，只能有一个终态。watchdog 不获取worker的执行锁；所有等待具备60秒本地截止，持久化不可用时也不能无限等候，返回固定基础设施错误，已有running行保留供恢复。
- 若 worker 不响应中断，HTTP 仍报告超时，锁保留到实际退出；不能启动同 Profile 后续工作。ReActLoop 在 LLM 前后、每个 Tool 前检查中断，避免受控执行在截止后启动新动作。迟到返回不能覆盖 timeout。
- 无强制线程终止、通知撤销、自动重放或 exactly-once 承诺。Spring shutdown 对 worker 发中断；剩余 running 在下次启动收敛为 unknown。

### 3. 成功、错误与审计

- ReActLoop 构造 ToolResponseMessage 时附内部 `oryxos.tool.success` Boolean；不属于配置键、API 字段或新持久化协议。Scheduler 记录执行前消息数量，仅检查同一 Session 对象的本轮增量。
- 成功条件：process 正常返回、未超时、最后一条为无 toolCalls 的 AssistantMessage、本轮 ToolResponse 的成功元数据全部明确 true；缺失元数据按失败而非猜测。有失败 Tool 即任务 failed，即使模型解释后返回普通文本；末尾仍是 ToolResponse 判轮数耗尽。
- metadata 不写入既有 Session JSON；不从重新读库的历史判断本轮结果，不解析 ERROR 文本、不添加 AgentService 公共方法。
- 引擎异常及截止不保证未完成会话已保存；成功会话必须先持久化再终结任务。模型/工具自身审计继续走原入口。
- 当前 ToolExecutor 对审计写入异常记录固定日志、不重放工具；保留其契约。任务级成功不等价于审计存储已完全健康，验收需单独查询审计证明齐全。011 Store 写入失败则对调用方报基础设施错误。
- error_message 使用固定中文分类（模型调用失败/工具执行失败/轮数耗尽/执行超时/进程中断结果未知），不存 provider 原始异常、工具参数或 webhook URL。日志附 taskId/executionId/sessionId，异常详情遵守既有脱敏约束。

### 4. 存储、接口与页面

精确字段/事务见 [data-model.md](data-model.md)，端口/HTTP/页面行为见 [contracts/schedules.md](contracts/schedules.md)。execution_id 是历史独立主键；success=null 表示 running，false+duration=null 表示进程中断未知，不能伪造耗时。

页面先完成真实接口再接入，只新增指定定时页写操作。使用 website 实际 token，无新 UI 依赖。立即执行无自动重试；失联后显示“结果待确认”，提示刷新历史，避免用户误以为未运行。

### 5. Harness 与交付验收

- 默认 gate：ScheduledTaskE2ETest 五步课件断言、Store 事务/迁移、AgentScheduler 控制/锁、ReAct 元数据/中断、Controller 四操作、MultiAgentIsolationTest、SchedulerStabilityTest。
- integration：SchedulerFlowIT 使用真实 DeepSeek 与本次天气、回环 webhook，连续两次各 3 LLM+2 Tool，同一 Session；失败域名拒绝、后续真实 cron 触发可用。不得只用 runNow 冒充到点调度。
- RestartRecoveryIT 使用测试 classpath 启动真实 main/serve 独立 JVM（避免 test 阶段尚未生成新 JAR），完成/在途两类重启；随后以完整 verify 产出的实际新 JAR再次验收入口。
- 记录迁移前/后库、终态与次数、确切审计和通知接收证据；无 key 绿灯不能替代真链路。
- 最后前端 build→完整 mvn clean verify（OWASP 不跳过）→显式 IT 与 WebSmokeIT→实际 JAR浏览器/入口检查→一致性复审及验收报告。若新修复改变实现，重跑对应检查及受影响门禁；不无理由重复全仓测试。
- 避开 P3C/ASM 不支持的语法；方法名英文，DisplayName 中文。三个课件固定测试名称如需 Checkstyle 缩写例外，只按具体类名添加，不新增通用安全忽略。

## Complexity Tracking

无宪法例外。相比 009 增加两表和 Spring 托管虚拟执行器，是已批准的持久化/隔离/截止所需；分布式锁、队列、通用 workflow 均未引入。

## 任务审阅时需关注的兼容改造

除课件明示的 Scheduler/Store/API/表/页面，还需修改既有 ReActLoop 内部元数据与中断点、CoreEngineConfiguration 初始化时序/执行器，以及测试中受装配改变影响的夹具。AgentScheduler 构造与管理查询/启停方法的精确加法列于 contracts；它们随本计划交付 tasks 审阅，不表示已改代码。公共 AgentService/SessionManager/MemoryService 签名不变。
