# Tasks: 三层 Memory 核心能力

**Branch**: `022-lesson22-memory` | **Created**: 2026-08-27

**Input**: [spec.md](spec.md)、[plan.md](plan.md)、[research.md](research.md)、[data-model.md](data-model.md)、[Memory contract](contracts/memory-contract.md)、[quickstart.md](quickstart.md)；宪法 v2.0.0。

**Status**: 规划完成，等待固定停点确认；未确认前不进入实现。

**Tests**: 本节是核心特性，测试必需。所有行为任务先写 harness 并确认因缺失行为失败，再实现跑绿；不得以编译失败替代行为红态，不删断言、不 `@Disabled`、不放宽门禁。测试方法名使用英文，课件中文守点放 `@DisplayName`。

## Format / 实施边界

- 每项严格采用 `- [ ] Tnnn [P?] [USn?] 描述 + 文件路径`；[P] 只表示不同文件且无未完成依赖。
- 仅长期记忆使用 `MEMORY.md`；会话历史复用既有 Session/SQLite，绝不新增 `memory_entries` 或其他表。
- 经用户批准，`MemoryService` / `MemoryScope` 端口归 core，`MemoryServiceImpl` / `LongTermMemory` / `MemoryTools` 归 memory；九模块不变。
- 不新增 `memory.backend`、Memory 路径配置、Profile 字段或第三方版本；不实现 SQLite 长期记忆、Mem0、向量、自动提炼、缓存、Memory Wiki 或多租户隔离。
- Memory Tool 必须走 `ToolRegistry → AnnotatedToolAdapter → ToolExecutor → tool_invocations` 唯一路径；普通 Java Plugin 的默认拒绝不得放宽。
- `.oryxos/memory/MEMORY.md` 是运行时生成资产，测试用 `@TempDir`；不得把本地用户 Memory 内容提交进仓库。
- 全程不自动 commit/push/package.sh；最终未跳过插件的 `mvn clean verify` 不绿不得封板。

## Phase 1: Setup（依赖与批准边界）

**Purpose**: 锁定现有依赖、单向模块图与已批准职责调整，不提前写业务实现。

- [ ] T001 复核 `docs/class/第22节：Memory 实现与代码讲解.md`、`docs/TechnicalSolution.md`、`docs/AiProgrammingGuide.md`、`AGENTS.md` 与 `specs/006-memory/plan.md` 的文件式范围及端口归属一致，并把H0 39/39前序类、Specify CLI 0.14.2、当前分支证据写入 `specs/006-memory/verification.md`
- [ ] T002 在 `oryxos-memory/pom.xml` 显式声明已锁定的 `spring-ai-model`，在 `oryxos-tool/pom.xml` 增加内部 `oryxos-memory` 依赖；不新增版本属性或外部依赖
- [ ] T003 运行 dependency tree 并核对 `pom.xml` 九模块及 `oryxos-tool → oryxos-memory → oryxos-core` 无环，把 Spring AI 1.1.8 与 `ToolParam.required/description` 本地 API 证据写入 `specs/006-memory/verification.md`

**Checkpoint**: 依赖可解析、模块数仍为9，端口迁移审批和事实源同步有证据。

---

## Phase 2: Foundational（核心端口与装配骨架）

**Purpose**: 建立所有故事共同依赖的唯一 Memory 契约，尚不实现文件行为。

- [ ] T004 [P] 在 `oryxos-core/src/main/java/com/oryxos/core/memory/MemoryScope.java` 创建仅含 `CORE` / `ARCHIVAL` 的端口枚举并补中文Javadoc
- [ ] T005 [P] 在 `oryxos-core/src/main/java/com/oryxos/core/memory/MemoryService.java` 按 contract 创建 `buildContext(Session,int)`、`remember(String,MemoryScope)`、`recall(String)` 唯一公共端口，不新增第二套Memory抽象
- [ ] T006 在 `oryxos-memory/src/main/java/com/oryxos/memory/package-info.java` 同步模块职责说明，运行core/memory test-compile证明端口方向可编译且core POM未新增memory依赖

**Checkpoint**: core 可独立编译端口，memory 只依赖 core 契约，不出现 core→memory 依赖。

---

## Phase 3: User Story 1 - 跨会话保留关键偏好 (Priority: P1) 🎯

**Goal**: 用工作区级双分区文件保存核心/归档长期记忆，跨实例读取，并保证核心不被归档截断影响。

**Independent Test**: 在 `@TempDir` 保存核心与超长归档，重新创建 LongTermMemory 实例后 load；核心逐字完整、注入视图只保留最近4000字、物理旧归档仍存在。

### Tests for User Story 1

- [ ] T007 [P] [US1] 在 `oryxos-memory/src/test/java/com/oryxos/memory/LongTermMemoryTest.java` 先写文件缺失/旧模板/无header旧内容/单分区无损升级、重复或倒序header失败且文件不变、CORE/ARCHIVAL带日期路由、4000/4001边界、物理文件不截断、跨实例无缓存、同路径并发无丢写、空内容/伪header/IO失败/USER.md不变的红态测试；原样落地 `truncationOnlyAffectsArchiveAndPreservesCore` 与 `writesAreVisibleImmediatelyWithoutCache`
- [ ] T008 [P] [US1] 在 `oryxos-cli/src/test/java/com/oryxos/cli/InitCommandTest.java` 先写 `@TempDir` + Picocli 测试，断言新工作区生成精确双分区模板且第二次init不覆盖已有内容

### Implementation for User Story 1

- [ ] T009 [US1] 在 `oryxos-memory/src/main/java/com/oryxos/memory/LongTermMemory.java` 实现显式workspace、双分区精确整行解析、旧格式无损升级、核心完整/归档4000字注入视图、每次现读、完整归档保留、同路径JVM锁与同目录临时文件原子替换；异常中文且不泄漏绝对路径，跑绿T007
- [ ] T010 [US1] 修改 `oryxos-cli/src/main/java/com/oryxos/cli/InitCommand.java`，保留无参入口并增加包级workspace构造供测试，默认 `MEMORY.md` 精确写双分区模板，既有工作区仍不覆盖，跑绿T008
- [ ] T011 [US1] 运行 `mvn -pl oryxos-memory -am test -Dtest=LongTermMemoryTest -Dsurefire.failIfNoSpecifiedTests=false` 与 `oryxos-cli` InitCommandTest，确认指定suite均执行非零用例并把退出码/数量写入 `specs/006-memory/verification.md`

**Checkpoint**: User Story 1 可独立证明文件跨实例持久、写后立即可见、核心区不受归档限长影响。

---

## Phase 4: User Story 2 - 主动归档并按关键词回忆 (Priority: P1)

**Goal**: Agent 通过两个内置 Tool 显式保存/回忆，scope缺省归档、关键词只搜完整归档，并保持统一安全与审计路径。

**Independent Test**: 用 mock MemoryService 验证两个 Tool 的输入/结果，再在 Spring Tool 配置中确认它们可执行而普通 Java Plugin 仍默认拒绝。

### Tests for User Story 2

- [ ] T012 [P] [US2] 在 `oryxos-memory/src/test/java/com/oryxos/memory/MemoryToolsTest.java` 先写两个精确 `@Tool` 名称、可选scope空缺省归档、trim/大小写、显式core、非法scope不调用service、recall命中换行拼接及未命中固定中文结果的红态测试
- [ ] T013 [P] [US2] 在 `oryxos-memory/src/test/java/com/oryxos/memory/MemoryServiceImplTest.java` 先写 remember/recall 仅委托LongTermMemory、null scope缺省归档、空内容/空关键词失败，以及非空长期记忆SystemMessage、空记忆无空消息、每次build现读、最近N条角色顺序、N=0/负数行为且不创建Session/不拼session_id的红态测试
- [ ] T014 [P] [US2] 在 `oryxos-tool/src/test/java/com/oryxos/tool/ToolConfigurationTest.java` 先加真实MemoryTools Bean测试：`save_memory`/`recall_memory` 在freeze前作为内置注册并可执行，重复名失败关闭，原普通Java插件仍默认拒绝，默认/用户Sandbox语义不变

### Implementation for User Story 2

- [ ] T015 [US2] 在 `oryxos-memory/src/main/java/com/oryxos/memory/MemoryServiceImpl.java` 完整实现 buildContext/remember/recall：每次load、非空长期记忆SystemMessage、最近N条原Message、N负数失败、输入校验与缺省scope；不创建额外Session或状态，跑绿T013
- [ ] T016 [US2] 在 `oryxos-memory/src/main/java/com/oryxos/memory/MemoryTools.java` 实现 `save_memory` / `recall_memory`，使用已核实的 `@ToolParam(required=false)`，scope按trim+Locale.ROOT解析，错误不泄漏Enum内部信息，跑绿T012/T013
- [ ] T017 [US2] 修改 `oryxos-tool/src/main/java/com/oryxos/tool/ToolConfiguration.java`，用 `ObjectProvider<MemoryTools>` 显式加入trusted builtins后再扫描普通插件；不得全局放行 `@Tool` Bean或改变registry freeze顺序，跑绿T014
- [ ] T018 [US2] 在 `oryxos-memory/src/main/java/com/oryxos/memory/MemoryConfiguration.java` 创建包私有AutoConfiguration，按 `.oryxos` workspace装配LongTermMemory、MemoryServiceImpl、MemoryTools，并在 `oryxos-memory/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 登记；不新增配置键
- [ ] T019 [US2] 运行 `mvn -pl oryxos-tool -am test -Dtest=LongTermMemoryTest,MemoryServiceImplTest,MemoryToolsTest,ToolConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false`，确认所有指定suite非零且普通插件拒绝回归仍绿，记录到 `specs/006-memory/verification.md`

**Checkpoint**: save/recall 已形成可独立测试的 Agent 长期记忆闭环，且没有绕过 ToolExecutor 或放宽插件安全边界。

---

## Phase 5: User Story 3 - 统一组装本轮记忆上下文 (Priority: P2)

**Goal**: PromptBuilder 通过唯一 MemoryService 得到非空长期记忆与最近会话历史，保持既有身份/Bootstrap/日期和工具契约。

**Independent Test**: 用 core fake MemoryService 构建 Prompt；验证消息顺序、角色、历史上限、空记忆退化和工具声明完全符合前序契约，无需真实文件。

### Tests for User Story 3

- [ ] T020 [P] [US3] 扩展 `oryxos-core/src/test/java/com/oryxos/core/react/PromptBuilderTest.java`，用fake MemoryService先写“身份/Bootstrap/日期→Memory→最近历史”顺序、空Memory退化、既有二参构造兼容、工具表声明顺序不漂移的红态回归
- [ ] T021 [P] [US3] 在 `oryxos-core/src/test/java/com/oryxos/core/config/CoreEngineConfigurationTest.java` 先写有MemoryService时注入真实端口、缺席时二参兼容空实现、toolTable限定不漂移且不加载memory实现类的红态装配测试
- [ ] T022 [P] [US3] 在 `oryxos-boot/src/test/java/com/oryxos/boot/MemorySystemIntegrationTest.java` 先写 `@Tag("integration")` 真实Spring装配：假LLM触发save_memory经ReAct/ToolExecutor写临时Memory，下一轮Prompt可见，重建Memory实例仍可读，并在临时SQLite断言恰一条最终成功tool_invocations

### Implementation for User Story 3

- [ ] T023 [US3] 修改 `oryxos-core/src/main/java/com/oryxos/core/react/PromptBuilder.java`，新增MemoryService正式构造并保留二参空记忆兼容入口，所有构造汇入同一build路径；删除重复历史截断职责但保持身份/Bootstrap/日期和工具表顺序，跑绿T020
- [ ] T024 [US3] 修改 `oryxos-core/src/main/java/com/oryxos/core/config/CoreEngineConfiguration.java`，通过 `ObjectProvider<MemoryService>` 给生产PromptBuilder注入真实端口，memory模块缺席时仅core测试使用空实现，不反向依赖MemoryServiceImpl，跑绿T021
- [ ] T025 [US3] 运行 `mvn -pl oryxos-core -am test -Dtest=PromptBuilderTest,CoreEngineConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false`，确认两suite非零且前序Prompt/ReAct回归无失败，记录到 `specs/006-memory/verification.md`
- [ ] T026 [US3] 运行quickstart中的显式 `MemorySystemIntegrationTest` 并跑绿T022；禁止新增第二套Tool执行/审计路径或修改 `oryxos-boot/src/main/java/com/oryxos/OryxOsApplication.java`，退出码/用例数/SQLite审计证据写入 `specs/006-memory/verification.md`

**Checkpoint**: 三个故事全部可独立验证，完整主链为“Session + MEMORY.md → MemoryService → PromptBuilder → ReAct”，Memory Tool 经统一执行与审计。

---

## Phase 6: Polish & Cross-Cutting（门禁与交接）

**Purpose**: 完成课件harness、前序回归、H4、完整安全门禁和人工项交接。

- [ ] T027 对照 `docs/class/第22节：Memory 实现与代码讲解.md` 核查 `LongTermMemoryTest`、`MemoryToolsTest`、`MemoryServiceImplTest`、`PromptBuilderTest` 均存在且非空，两个课件关键守点断言逐条保真；结果写入 `specs/006-memory/verification.md`
- [ ] T028 运行 `mvn test` 和quickstart显式 `MemorySystemIntegrationTest`，核对九模块前序测试与指定integration均执行非零用例且全绿；失败修实现，不删断言或弱化默认测试
- [ ] T029 核对交付物存在性与范围：core两端口、memory三类+包私有配置、双分区初始化、Prompt/Tool接线存在；`rg`确认无 `memory_entries`、`memory.backend`、Mem0/向量/缓存实现、USER.md写路径或新增第10模块，把证据写入 `specs/006-memory/verification.md`
- [ ] T030 执行H4六项自查：Memory外无新增涉外IO；LLM/tool审计仍落库；无明文key；session_id仍只由JpaSessionManager拼接；无Reactor/CompletableFuture/自建线程池；Spring AI自动工具执行仍关闭，逐项证据写入 `specs/006-memory/verification.md`
- [ ] T031 运行快速 `mvn clean verify -Ddependency-check.skip=true`，记录195项既有基线加本节新增用例以及Spotless/P3C/Checkstyle/PMD/SpotBugs/FindSecBugs结果；只能称快速门禁
- [ ] T032 运行未跳过任何插件的 `mvn clean verify`，确认OWASP Dependency-Check实际执行且九模块全部成功；完整门禁失败不得勾选或封板
- [ ] T033 对 `specs/006-memory/spec.md`、`plan.md`、`tasks.md` 与实现运行 `speckit-analyze`，发现遗漏则追加稳定任务ID、修复并重跑相关门禁，不仅靠勾选宣布完成
- [ ] T034 更新 `specs/006-memory/quickstart.md` 与 `specs/006-memory/verification.md`，交付六项DoD、改动导读、重点review位置、可复制命令及剩余人工项：真模型主动save、新会话/重启召回、USER.md只读、Memory Tool审计目检

---

## Dependencies & Execution Order

```text
Setup T001–T003
  → Foundation T004–T006
    → US1 T007–T011（文件持久化）
      ├→ US2 T012–T019（Agent save/recall + Tool注册）
      └→ US3测试 T020–T022 可提前准备
             → US3实现 T023–T026（统一Prompt主链）
                 → Polish T027–T034
```

- US1与US2同为P1；完整用户MVP需要二者，但各自harness可独立运行。
- T007/T008可并行；T012/T013/T014可并行；T020/T021/T022在共享实现修改前可并行写红态测试。
- `MemoryServiceImplTest` 在US2/US3串行扩展；`PromptBuilder.java`、`ToolConfiguration.java`、各POM不得交给多个并行执行者同时修改。
- 所有实现必须在对应红态harness确认后开始；编译失败只用于接口搭建诊断，不算行为红态。

## Parallel Examples

### User Story 1

```text
T007 LongTermMemory文件契约测试
T008 InitCommand双分区模板测试
```

### User Story 2

```text
T012 MemoryTools参数与结果测试
T013 MemoryServiceImpl委托测试
T014 ToolConfiguration内置/插件边界测试
```

### User Story 3

```text
T020 MemoryServiceImpl上下文测试
T021 PromptBuilder端口集成测试
T022 Boot完整链路integration测试
```

## Requirements → Tasks

| 需求 | 任务 |
|---|---|
| FR-001 / FR-018 | T001–T006、T021、T024、T025、T029 |
| FR-002–FR-008 | T007–T011、T020、T023 |
| FR-009 / FR-010 | T007、T012、T013、T015、T016 |
| FR-011 / FR-012 | T020、T021、T023–T025 |
| FR-013 | T012、T014、T016–T019、T022、T026 |
| FR-014–FR-016 | T007–T010、T029、T030 |
| FR-017 | T001–T003、T029、T032、T033 |
| SC-001–SC-006 | T007–T013、T020–T023、T027、T028 |
| SC-007 / SC-008 | T022、T026、T028–T034 |

## Implementation Strategy

### MVP First

1. 完成Setup与Foundation。
2. 完成US1，证明双分区文件持久、无缓存、核心不截断。
3. 完成US2，形成Agent可用的save/recall闭环——US1+US2是本节最小可演示MVP。
4. 完成US3，把Memory接入真实Prompt/ReAct主链。
5. 完成全部本地与安全门禁，再交人工真模型验证。

### 固定停点

任务清单生成后必须先与课件交付物逐项对账并等待用户确认；未确认前不执行T001，不写生产代码，不自动commit/push/package.sh。

**任务统计**: 34项；Setup 3、Foundation 3、US1 5、US2 8、US3 7、Polish 8。标注[P] 10项。MVP为US1+US2；US3完成后形成完整ReAct主链。
