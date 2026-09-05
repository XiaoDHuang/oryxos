---

description: "009-agent-scheduler 任务清单（第25节：定时任务模块）"
---

# Tasks: 定时任务（第三种触发源）

**Input**: Design documents from `/specs/009-agent-scheduler/`

**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/, quickstart.md

**Tests**: 宪法要求核心特性必须有可独立运行的自动化验收；本节 harness 为 `AgentSchedulerTest` 一个类覆盖四个坑 + 装配门控回归，测试任务先于或伴随对应实现任务（harness 先行）。

**Organization**: 按 spec.md 三个 user story 组织。说明：课件把 runOnce 骨架作为不可拆的整体给出（拿锁→执行→放锁一体），因此实现主体在 US1 一次成型，US2/US3 阶段各补其 harness 用例把对应语义钉死（若红修实现，禁改测试迁就）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 不同文件、无未完成依赖，可并行
- **[Story]**: US1/US2/US3 对应 spec.md 用户故事

## Phase 1: Setup

**Purpose**: 开工基线确认

- [x] T001 确认在 `025-lesson25-scheduler` 分支且工作区干净，跑 `mvn -pl oryxos-core -am -q compile` 确认基线可编译（research.md R1/R2 的 API 核实证据已留存，此处只复核基线）

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 三个故事共享的类型地基：ScheduleConfig、Profile 强类型化、ProfileRegistry.all()

**⚠️ CRITICAL**: 未完成前不得开始任何 user story

- [x] T002 [P] 新建 `ScheduleConfig` record（id/cron/zone/message 四 String 分量，`@JsonIgnoreProperties(ignoreUnknown = true)`，中文 Javadoc 只写为什么）在 `oryxos-core/src/main/java/com/oryxos/core/profile/ScheduleConfig.java`
- [x] T003 `Profile.schedules` 由 `List<Map<String, Object>>` 强类型化为 `List<ScheduleConfig>`（规范构造器 null→空表不变）在 `oryxos-core/src/main/java/com/oryxos/core/profile/Profile.java`；同步适配 `oryxos-core/src/test/java/com/oryxos/core/profile/ProfileLoaderTest.java` 夹具（`name: morning-report` → `id/cron/zone/message` 四要素）与断言（`profile.schedules().get(0).id()` 等 record 访问）；全库 grep `new Profile(` 确认无其他构造点编译破坏（依赖 T002）
- [x] T004 [P] `ProfileRegistry` 新增 `all()` 返回全部已注册 Profile（不可变视图，中文注释注明"课件 registerAll 枚举需求"）在 `oryxos-core/src/main/java/com/oryxos/core/profile/ProfileRegistry.java`

**Checkpoint**: `mvn -pl oryxos-core -am test` 绿——类型地基就绪

---

## Phase 3: User Story 1 - 配置驱动的到点自动触发 (Priority: P1) 🎯 MVP

**Goal**: Profile 声明四要素规则，常驻模式启动注册（CronTrigger 携带 cron+时区），到点拼消息走与人推相同的 AgentService.process；非法规则跳过不阻断；chat 不注册

**Independent Test**: AgentSchedulerTest US1 用例 + CoreEngineConfigurationTest 门控两例全绿；`mvn -pl oryxos-core -am test` 通过

### Tests for User Story 1 ⚠️（先于实现写，确认红）

- [x] T005 [US1] 建档 `oryxos-core/src/test/java/com/oryxos/core/schedule/AgentSchedulerTest.java` 并写 US1 用例（方法名英文、课件原文进 `@DisplayName`）：①`registerAll` 后 ArgumentCaptor 捕获 `schedule(Runnable, Trigger)` 参数，断言 `isEqualTo(new CronTrigger(cron, ZoneId.of(zone)))`（等值覆盖 cron+时区，research R3）；②手动调 `runOnce` 断言 `agentService.process` 被以该 Profile 的会话与 message 调用；③FR-008 三例：cron 非法 / 要素缺失 / id 重复时 `taskScheduler.schedule` 对该条零调用且不抛异常

### Implementation for User Story 1

- [x] T006 [US1] 实现 `oryxos-core/src/main/java/com/oryxos/core/schedule/AgentScheduler.java`：纯 POJO（不带 Spring 注解），构造注入 `ThreadPoolTaskScheduler`/`ProfileRegistry`/`AgentService`/`SessionManager`；`registerAll()` 枚举 `profileRegistry.all()` 逐条校验（四要素非空白、id 去重、CronTrigger/ZoneId 可构造）后 `taskScheduler.schedule(() -> runOnce(profile, sc), new CronTrigger(sc.cron(), ZoneId.of(sc.zone())))`，非法条日记中文错误日志跳过；`runOnce` 完整落地课件骨架：`taskLocks.computeIfAbsent(sc.id(), ...)` + `tryLock` 拿不到则记日志返回、try 内 `sessionManager.getOrCreate("scheduler","scheduler",profile.name())` 后 `agentService.process(session, sc.message())`、catch Exception 只记中文 error 日志、finally unlock；`lockFor(String)` 包私有供测试；常量 channel/user="scheduler"；避开 P3C 禁区语法；使 T005 转绿
- [x] T007 [US1] `CoreEngineConfiguration` 新增 `agentScheduler` @Bean：注入 `ThreadPoolTaskScheduler`/`ProfileRegistry`/`AgentService`/`SessionManager` 四个依赖，方法标注 `@ConditionalOnProperty(prefix="oryxos.scheduler", name="enabled", havingValue="true")`（命中才生成 Bean 并无条件 `registerAll()`），注释说明"仅常驻模式注册（§8.6，用户决议）"在 `oryxos-core/src/main/java/com/oryxos/core/config/CoreEngineConfiguration.java`
- [x] T008 [P] [US1] `CoreEngineConfigurationTest` 补两例：直调工厂方法时 `ThreadPoolTaskScheduler.schedule` 被调用；反射钉住 `@ConditionalOnProperty` 的 prefix/name/havingValue（缺省属性→chat 注册数为零）在 `oryxos-core/src/test/java/com/oryxos/core/config/CoreEngineConfigurationTest.java`
- [x] T009 [P] [US1] `ServeCommand` 与 `GatewayCommand` 的 `SpringRuntime.start(...)` 追加 `"--oryxos.scheduler.enabled=true"`；`ChatCommand` 不动，在 `oryxos-cli/src/main/java/com/oryxos/cli/ServeCommand.java`、`oryxos-cli/src/main/java/com/oryxos/cli/GatewayCommand.java`
- [x] T010 [US1] 跑 `mvn -pl oryxos-core,oryxos-cli -am test` 全绿（先 `mvn -pl oryxos-core,oryxos-cli spotless:apply` 过格式门禁）

**Checkpoint**: US1 闭环可独立演示——常驻模式注册、到点触发、非法跳过、chat 不注册

---

## Phase 4: User Story 2 - 重叠跳过与失败隔离 (Priority: P2)

**Goal**: 锁占时本次触发直接跳过；执行抛异常不外抛、finally 放锁（语义随 T006 一次成型，本阶段用 harness 用例钉死）

**Independent Test**: AgentSchedulerTest 新增两例绿：`process` 零调用（锁占）、`times(2)`（二进宫）

### Tests for User Story 2 ⚠️

- [x] T011 [US2] `AgentSchedulerTest` 补两例（`@DisplayName` 保留课件原文）：①"上一次还没跑完_本次触发直接跳过"——锁由**另一线程**持住后 `runOnce`（课件单线程 lock 写法对可重入 ReentrantLock 测不到跳过语义，属课件示意缺陷，此处按真实重叠场景修正、断言 `verify(agentService, never()).process(any(), any())` 逐字保真）；②"任务抛异常_不外抛且锁必须被释放"——`process` 抛 RuntimeException，`assertDoesNotThrow(runOnce)` 后再触发一次，`verify(agentService, times(2))`；跑 `mvn -pl oryxos-core -am test` 绿（若红，修 AgentScheduler 实现，禁动断言）

**Checkpoint**: US1+US2 均独立成立——主链路稳、防护语义有据

---

## Phase 5: User Story 3 - 定时触发的会话身份与历史连续性 (Priority: P3)

**Goal**: 固定三元组 ("scheduler","scheduler",profileName)，同 Profile 历次触发同一 Session（语义随 T006 成型，本阶段钉死）

**Independent Test**: AgentSchedulerTest 新增两例绿

### Tests for User Story 3 ⚠️

- [x] T012 [US3] `AgentSchedulerTest` 补两例：①两次 `runOnce` 后 `verify(sessionManager, times(2)).getOrCreate("scheduler","scheduler",profileName)` 且两次 `process` 收到同一 Session 实例（mock getOrCreate 返回同一对象）；②三元组参数逐字为 "scheduler"/"scheduler"/profile 名（课件原文进 `@DisplayName`）；跑 `mvn -pl oryxos-core -am test` 绿（若红修实现）

**Checkpoint**: 三个故事全部独立成立，harness 四回归点齐全

---

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T013 同步 `AGENTS.md`「项目现状」段：补 009 定时任务记录（25 节交付，harness 全绿口径），不动其他既有表述
- [x] T014 全量门禁 `mvn clean verify` 九模块（Spotless/P3C/Checkstyle/SpotBugs/FindSecBugs/PMD/Dependency-Check + 全部测试），确认前序节回归全绿
- [x] T015 按 quickstart.md 列出人工验证移交清单（真实到点触发看审计、chat 不注册体感、改 cron 重启生效、非法 zone 跳过），写进节验收报告

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖
- **Foundational (Phase 2)**: 依赖 Phase 1——阻断所有故事（类型地基）
- **User Stories (Phase 3+)**: 均依赖 Phase 2；US1 承载实现主体，US2/US3 依赖 US1 的实现（仅补测试钉语义）
- **Polish (Phase 6)**: 依赖全部故事完成

### User Story Dependencies

- **US1 (P1)**: Foundational 完成后开始，无其他故事依赖
- **US2 (P2)**: 依赖 US1 的 runOnce 实现（测试钉死其锁/异常语义）
- **US3 (P3)**: 依赖 US1 的 runOnce 实现（测试钉死其会话身份语义）

### Parallel Opportunities

- T002 ∥ T004（不同文件）；T003 须等 T002
- T008 ∥ T009（core 测试 ∥ cli 命令）

## Parallel Example

```bash
# Phase 2 内并行：
Task: "新建 ScheduleConfig record（oryxos-core/.../profile/ScheduleConfig.java）"
Task: "ProfileRegistry 新增 all()（oryxos-core/.../profile/ProfileRegistry.java）"

# Phase 3 内并行：
Task: "CoreEngineConfigurationTest 门控两例"
Task: "ServeCommand/GatewayCommand 传启用参数"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1 + Phase 2 → 类型地基
2. Phase 3 US1 → 常驻注册 + 到点触发闭环可演示
3. **STOP and VALIDATE**: T010 全绿

### Incremental Delivery

1. US1 → 主链路（MVP）
2. US2 → 锁/失败语义钉死
3. US3 → 会话身份语义钉死
4. Phase 6 → 文档同步 + 全量门禁 + 人工项移交

---

## Notes

- [P] = 不同文件、无未完成依赖
- 测试方法名英文，课件中文用例名进 `@DisplayName` 以便对号
- 每个任务完成后跑所在模块测试，红了当场修
- 新 Java 文件先 `mvn -pl <模块> spotless:apply` 再跑测试，避免格式门禁拦截
- 不自动 commit——同步时机由用户决定
