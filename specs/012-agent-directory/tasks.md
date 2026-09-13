# Tasks: 插件化 Agent 目录——一个目录定义一个会自己跑的 Agent

**Input**: Design documents from `/specs/012-agent-directory/`
**Prerequisites**: plan.md ✅ spec.md ✅ research.md ✅ data-model.md ✅ contracts/ ✅ quickstart.md ✅

**Tests**: 课件验收 harness 六个测试类为硬交付，测试任务先于对应实现任务（先红后绿）。测试方法名英文，`@DisplayName` 保留中文语义。新 Java 文件写完先 `mvn -q -pl oryxos-core spotless:apply` 再跑测试。

**Organization**: 任务按 spec.md 用户故事组织；US3 的运行时注册机制是 US1 扫描装配的前置（同为前序类改造点），故落在 Foundational。

## Phase 1: Setup

- [x] T001 核实 oryxos-core 依赖树中 SnakeYAML 存在（`mvn -q -pl oryxos-core dependency:tree | grep -i snakeyaml`），确认 frontmatter 解析无新依赖（H3 写前核实，research D1）
- [x] T002 [P] 按课件 §1.3–1.4 全文产出示例 Agent 四件套：`specs/012-agent-directory/samples/daily-reconcile/AGENT.md`、`REFERENCE.md`、`skills/report-format.md`、`scripts/reconcile.py`（字面内容逐字取自课件，脚本为纯标准库）

## Phase 2: Foundational（前序类改造点，阻断 US1/US3）

- [x] T003 [P] 先红：写 `oryxos-core/src/test/java/com/oryxos/core/profile/ProfileRegistryRuntimeTest.java`——register() 后 find() 立即可见、exists/remove 语义、非法配置（缺 name、provider 未声明）抛 `IllegalArgumentException` 且消息与启动加载路径逐字一致（harness：ProfileRegistryRuntimeTest）
- [x] T004 实现 `oryxos-core/src/main/java/com/oryxos/core/profile/ProfileValidator.java`（package-private：name 必填、provider 在全局名集合，失败抛固定中文消息的 IllegalArgumentException），改造 `oryxos-core/src/main/java/com/oryxos/core/profile/ProfileRegistry.java`（保序 Map + synchronized register/remove/exists，register 先校验），改造 `oryxos-core/src/main/java/com/oryxos/core/profile/ProfileLoader.java`（校验改调 ProfileValidator，日志与跳过行为不变）；跑 T003 转绿 + `ProfileLoaderTest` 全绿回归
- [x] T005 [P] 先红：写 `oryxos-core/src/test/java/com/oryxos/core/schedule/AgentSchedulerRegisterTest.java`——registerProfile 后 cronFutures 句柄表有句柄、cron/时区/message 逐字来自 Profile.schedules、非法规则跳过不阻断（harness：AgentSchedulerRegisterTest）
- [x] T006 实现 `oryxos-core/src/main/java/com/oryxos/core/schedule/AgentScheduler.java` 的 registerProfile(Profile) 抽取（registerAll 循环体原样搬出，清表重扫与定义消失清扫语义不变，句柄表沿用 cronFutures）；跑 T005 转绿 + `AgentSchedulerTest` 全绿回归

**Checkpoint**: 运行时注册机制就位（US3 机制面完成），16/25/28 节回归全绿

## Phase 3: User Story 1 - 丢一个目录就定义出一个 Agent (Priority: P1) 🎯 MVP

**Goal**: agents/ 目录扫描 → 解析 → 派生 Profile → 同一套校验 → 注册（可见、同名跳过、未注册能力告警）

**Independent Test**: 放 N 个目录（含一个缺必填坏目录）→ 注册表 N 个可见、坏目录点名跳过、其余不受影响

- [x] T007 [P] [US1] 先红：写 `oryxos-core/src/test/java/com/oryxos/core/agent/AgentLoaderTest.java`——frontmatter 与正文正确拆分、认出 scripts/skills/REFERENCE 资源、缺 AGENT.md/坏 YAML/缺 name/provider 报错点名（harness：AgentLoaderTest）
- [x] T008 [P] [US1] 先红：写 `oryxos-core/src/test/java/com/oryxos/core/agent/DeriveProfileTest.java`——frontmatter 各字段一一映射到 Profile、schedules 原样带进、identity.promptFile 置为 `agents/<目录名>/AGENT.md`（harness：DeriveProfileTest）
- [x] T009 [US1] 实现 `oryxos-core/src/main/java/com/oryxos/core/agent/AgentDefinition.java` 与 `oryxos-core/src/main/java/com/oryxos/core/agent/AgentLoader.java`（SnakeYAML 拆 `---` 围栏、camelCase 归一化 + ${ENV} 解析与 ProfileLoader 同规则、资源只记位置不读内容）；跑 T007/T008 转绿
- [x] T010 [P] [US1] 先红：写 `oryxos-core/src/test/java/com/oryxos/core/agent/AgentScanRegisterTest.java`——扫 N 个目录注册 N 个、带 schedules 的经 scheduler.registerProfile 进调度器、缺必填目录点名跳过不阻断、同名跳过不覆盖、未注册工具告警（harness：AgentScanRegisterTest）
- [x] T011 [US1] 实现 `oryxos-core/src/main/java/com/oryxos/core/agent/AgentDirectoryScanner.java`（构造供入 registry/validator/工具名集合(null 跳过告警)/scheduler(null 跳过定时)），并在 `oryxos-core/src/main/java/com/oryxos/core/config/CoreEngineConfiguration.java` 装配 agentDirectoryScanner Bean（依赖 toolTable ObjectProvider 与 ObjectProvider<AgentScheduler>）；跑 T010 转绿 + `CoreEngineConfigurationTest` 回归

**Checkpoint**: 丢目录 → 启动后列表可见，MVP 成立

## Phase 4: User Story 2 - Agent 到点自己跑，资源按需进上下文 (Priority: P2)

**Goal**: 正文每次触发现读进 system prompt（去 frontmatter）；参考/子指令/脚本不预载，只经底座 read_file/shell 按需取用

**Independent Test**: 派生 Profile 触发时 system prompt 含正文不含 frontmatter 与资源内容；改文件后再次 load 即得新正文

- [x] T012 [P] [US2] 先红：写 `oryxos-core/src/test/java/com/oryxos/core/context/ProgressiveDisclosureTest.java`——promptFile 正文进 load 结果且不含 frontmatter 键、目录内 REFERENCE/skills/scripts 内容不预载、两次调用之间改文件第二次即见新内容（无缓存）（harness：ProgressiveDisclosureTest）
- [x] T013 [US2] 实现 `oryxos-core/src/main/java/com/oryxos/core/context/ContextLoader.java` 的 promptFile 加法（identity.promptFile 非空→工作区根现读→以 `---` 开头且有闭合围栏则剥离→正文拼入；缺失抛 IllegalStateException）；跑 T012 转绿 + `ContextLoaderTest` 全绿回归

**Checkpoint**: 渐进式披露守点钉死；定时来自 Agent 已由 T006/T008/T010 三处断言覆盖

## Phase 5: User Story 3 - 运行时不重启注册/摘除 Agent (Priority: P3)

**Goal**: 机制已在 Phase 2 交付；本相核对三条验收场景全部有测试对号且跑绿

**Independent Test**: ProfileRegistryRuntimeTest + AgentSchedulerRegisterTest 全绿即满足

- [x] T014 [US3] 核对并跑绿 US3 三则验收场景：`mvn -q -pl oryxos-core test -Dtest='ProfileRegistryRuntimeTest,AgentSchedulerRegisterTest'`——运行时可见、同一异常同一消息、句柄表有句柄（零新增代码，证据记入验收报告）

## Phase 6: Polish & Cross-Cutting

- [x] T015 同步示例到本地工作区 `.oryxos/agents/daily-reconcile/`（gitignored，供手工验证），确认 `oryxos profile list` 路径可见该 Agent（quickstart 手工项的前半）
- [x] T016 文档同步：`AGENTS.md` 的".oryxos/ 工作区结构"段补 `agents/` 目录与"AGENT.md frontmatter 派生到同一 Profile"一句；`docs/TechnicalSolution.md` §11.2 补 Agent 目录作者路径（与手写 YAML 并存）
- [x] T017 全量门禁 `mvn clean verify` 全绿（含 P3C/SpotBugs/FindSecBugs/PMD/OWASP），确认前序各节测试零回退；贴关键输出进验收报告

## Dependencies

- T001 → 全部实现任务（H3 核实）
- Phase 2（T003–T006）→ 阻断 Phase 3（scanner 依赖 register/validator/registerProfile）
- Phase 3 → Phase 4 无硬依赖（ContextLoader 加法独立），但按优先级顺序交付
- Phase 5 仅依赖 Phase 2
- Phase 6 依赖全部前序

## Parallel Execution Examples

```bash
# Phase 2 两个先红测试可同时写：
T003 ProfileRegistryRuntimeTest ∥ T005 AgentSchedulerRegisterTest
# Phase 3 三个先红测试可同时写：
T007 AgentLoaderTest ∥ T008 DeriveProfileTest ∥ T010 AgentScanRegisterTest
# 样本文件（T002）与任何代码任务并行
```

## Implementation Strategy

1. MVP 先走 T001→T011：目录丢进去就能被看见。
2. 每个实现任务执行前先把对应测试跑红，实现后单模块转绿，再跑该模块全量回归（`mvn -q -pl oryxos-core -am test`）。
3. 语法禁区：不用增强 switch 的 `default ->` 等 P3C/ASM 解析不了的 Java 18+ 形态；catch 不吞异常；注释只写"为什么"、中文、Javadoc 首句以 `.` 收尾。
4. T017 之前不做任何 commit；同步时机由用户决定。
