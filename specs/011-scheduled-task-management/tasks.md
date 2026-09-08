# Tasks: 持久化定时任务与运行管理

**Input**: `specs/011-scheduled-task-management/` 的 spec/plan/research/data-model/contracts/quickstart。
**Status**: 任务审阅稿，0/40 实现任务完成。D28 范围已批准，tasks 固定停点尚未解除。
**Tests**: 核心功能按宪法与课件先写/伴随测试；主模型实现与回归，不调度 Spark。课件三个指定 harness 及多 Agent/稳定性均纳入。

## Phase 1: Setup

- [x] T001 核对 `specs/011-scheduled-task-management/spec.md`、`plan.md`、`research.md` 与实际 HEAD/用户审批；记录开工状态到本 feature `acceptance.md`，确认 Spec Kit0.14.2、九模块、D28-01–04、无新依赖。（FR-001/015）
- [x] T002 核对 `oryxos-boot/src/test/java/com/oryxos/boot/HumanFlowFixture.java`、`MockAgentE2ETest.java` 与27验收证据；建立 `.verification/lesson28/` 隔离验证目录，记录现有服务/工作区边界和秘密注入流程到本 feature `acceptance.md`。（FR-015）

## Phase 2: Foundational

- [x] T003 在 `oryxos-core/src/main/java/com/oryxos/core/schedule/` 新建 `ScheduledTaskStore.java`、`ScheduledTaskView.java`、`TaskExecutionView.java`，按 `contracts/schedules.md` 锁定字段/方法/nullable/分页语义；只定义端口与不可变视图，不创建假 Store。（FR-002/007/009）
- [x] T004 [P] 在 `oryxos-storage/src/test/java/com/oryxos/storage/schedule/JpaScheduledTaskStoreTest.java` 先写真实临时SQLite的旧四表升级、重复schema、task/execution独立主键及初始/空历史断言，确认新Store未实现时红灯。（FR-002）
- [x] T005 [P] 在 `oryxos-core/src/test/java/com/oryxos/core/react/ReActLoopTest.java` 先写本轮成功metadata、工具失败后普通回复仍可识别、正常工具正文以ERROR开头不误判、最大轮数尾消息和中断后不再启动LLM/Tool的回归断言；保留既有轮次/工具执行断言。（FR-007/013）

## Phase 3: US1 任务登记与持久化 (P1)

**Goal**: 定义、启停、开始/终态及历史可持久恢复。
**Independent Test**: JpaScheduledTaskStoreTest 使用真实SQLite检验登记与原子计数，不依赖Web/真实LLM。

- [x] T006 [US1] 在 `oryxos-storage/src/main/resources/db/schema.sql` 幂等追加两表/约束/索引；新增 `oryxos-storage/src/main/java/com/oryxos/storage/schedule/ScheduledTask.java`、`TaskExecution.java` 及 `ScheduledTaskRepository.java`、`TaskExecutionRepository.java`，保持UTC毫秒与旧四表不变。（FR-002）
- [x] T007 [US1] 实现 `oryxos-storage/src/main/java/com/oryxos/storage/schedule/JpaScheduledTaskStore.java` 的登记、find/list/page/count/enable/nextRun，保持同id所属校验与状态保留，严格页边界；T004转绿。（FR-001/002/003/009）
- [x] T008 [US1] 扩展 `oryxos-storage/src/test/java/com/oryxos/storage/schedule/JpaScheduledTaskStoreTest.java`：同id换Profile拒绝、disabled登记不重置、begin失败零历史、begin单增量、finish回滚一致性、重复finish、同时间稳定分页、unknown恢复/不重放/重复恢复；先红后实现。（FR-002/003/007/008/011）
- [x] T009 [US1] 在 `oryxos-storage/src/main/java/com/oryxos/storage/schedule/JpaScheduledTaskStore.java` 完成begin/finish/recoverInterrupted短事务和条件终态，锁定每次begin仅加一次runCount、未知耗时NULL；保持网络调用不在DB事务中。（FR-007/008/011）
- [x] T010 [US1] 运行Store与原Session/审计/Memory实体回归：`mvn -pl oryxos-storage -am test`；将旧库/重复初始化/事务证据记到 `specs/011-scheduled-task-management/acceptance.md`。（FR-002/008/011，SC-001/002）

## Phase 4: US2 自动触发、立即执行和启停 (P1)

**Goal**: 钟推与手推共用引擎，防重叠、记录真实结果与60秒截止。
**Independent Test**: AgentSchedulerTest 通过受控引擎驱动真实准入/状态逻辑；Store已由US1单独验证。

- [x] T011 [US2] 扩展 `oryxos-core/src/test/java/com/oryxos/core/schedule/AgentSchedulerTest.java`：登记保状态、非法/重复/缺失规则、重复registerAll不重复安装、停用自动零调用、disabled runNow可执行、同任务/同Profile忙碌零历史、不同Profile可执行，测试先行。（FR-001/003/004/005）
- [x] T012 [US2] 改造 `oryxos-core/src/main/java/com/oryxos/core/schedule/AgentScheduler.java`，接Store/虚拟executor，保留规则id及固定Session三元组；实现catalog、runNow/isRegistered/setEnabled、future取消、nextRun推进/失效清空及准入锁。（FR-001/003/004/005/006）
- [x] T013 [US2] 修改 `oryxos-core/src/main/java/com/oryxos/core/react/ReActLoop.java` 的内部ToolResponse metadata与LLM前后/Tool前中断点；T005转绿，不改public入口或Session JSON。（FR-007/013）
- [x] T014 [US2] 在 `oryxos-core/src/test/java/com/oryxos/core/schedule/AgentSchedulerTest.java` 先补普通答复掩盖Tool失败、缺metadata、轮数耗尽、异常、begin/finish存储失败、watchdog/正常完成竞态、迟到返回不覆盖timeout、锁留至真实退出、执行中停用无需等待worker结束、存储阻塞时等待仍有本地截止的断言。（FR-004/007/008/013）
- [x] T015 [US2] 在 `oryxos-core/src/main/java/com/oryxos/core/schedule/AgentScheduler.java` 实现worker开始/终态、私有等待载体、60秒watchdog、中断、结果分类与日志关联；固定中文错误不暴露参数，Store失败不得报告成功。（FR-006/007/008/013/015）
- [x] T016 [US2] 更新 `oryxos-core/src/test/java/com/oryxos/core/config/CoreEngineConfigurationTest.java` 的启动时序/构造适配，先验schema-ready后登记、chat零注册、Spring托管虚拟worker及关闭取消；保留原cron/zone/条件属性守点。（FR-001/005/013）
- [x] T017 [US2] 修改 `oryxos-core/src/main/java/com/oryxos/core/config/CoreEngineConfiguration.java`：包私有托管虚拟执行器、仅常驻模式装配、上下文初始化后recover/register、关闭清理；同步所有受影响构造调用（经rg定位），不使用内存Store兜底。（FR-001/005/011/013）
- [x] T018 [US2] 运行core/storage及受影响boot装配测试，真实同Profile会话复用/次数核对，并在 `specs/011-scheduled-task-management/acceptance.md` 记录定向测试与失败原始证据。（FR-004/005/006/007/008，SC-001）

## Phase 5: US3 管理接口和定时任务页面 (P2)

**Goal**: 四端点与真实页面支持查询/执行/启停，保留旧页只读。
**Independent Test**: Controller契约测试与浏览器完整操作，成功/失败业务状态均可见。

- [x] T019 [P] [US3] 新建 `oryxos-web/src/test/java/com/oryxos/web/api/ScheduleApiControllerTest.java`：四操作/信封/分页、严格PUT字段/类型、未知404、忙碌/失效/非调度模式400、业务失败200+false、timeout504、Store故障500、执行ID字符串与特殊id编码，先写断言。（FR-009/015）
- [x] T020 [P] [US3] 新建 `oryxos-boot/src/test/java/com/oryxos/boot/ScheduledTaskE2ETest.java` 与包私有 `SchedulerFlowFixture.java`：真实整机/临时SQLite/生产mock，课件五步登记→run→2LLM1save_memory→查记忆/历史/次数→停用；加默认markdown文件未被误认为SQLite记忆的核对。（FR-002/004/006/007/009）
- [x] T021 [US3] 实现 `oryxos-web/src/main/java/com/oryxos/web/api/ScheduleApiController.java` 与嵌套DTO，注入Store及可选Scheduler，严格PUT/路径/分页检查；run包Callable，按contract映射既有错误，不改全局其他端点语义。（FR-009）
- [x] T022 [US3] 扩展 `oryxos-boot/src/test/java/com/oryxos/boot/ScheduledTaskE2ETest.java` 验真实自动停用跳过/disabled手动执行、当前失效规则只读、重复请求占用和真实六仓储扫描；适配旧 `MockAgentE2ETest.java` 的四仓储断言为显式新六仓储集合，保留旧四个必需仓储检查。（FR-003/004/005/009）
- [x] T023 [US3] 扩展 `oryxos-web/src/main/frontend/src/api.js` 的POST/PUT封装并保留apiGet；实现 `src/views/SchedulesView.vue` 的真实列表/分页历史/行级执行和启停、失败200呈现与未知结果历史复核，遵守admin-ui Skill。（FR-009/010）
- [x] T024 [US3] 修改 `oryxos-web/src/main/frontend/src/main.js`、`src/App.vue`、`src/assets/main.css` 加第六页导航/响应式，沿website token；不改原五页读写权限。（FR-010）
- [x] T025 [US3] 在 `oryxos-web/src/main/frontend/` 执行npm ci/build，更新 `oryxos-web/src/main/resources/static/admin/`；对真实API做桌面/375px、三态、busy/业务失败/超时/防重复提交、子路由刷新检查，证据存 `.verification/lesson28/` 并引用到 `acceptance.md`。（FR-010，SC-005）
- [x] T026 [US3] 更新 `README.md` 四接口表与操作说明（不把任务API200等同业务成功），运行web/boot无key测试及旧WebSmokeIT；记录到 `specs/011-scheduled-task-management/acceptance.md`。（FR-009/010，SC-001/005）

## Phase 6: US4 真链路、重启与多 Agent (P2)

**Goal**: 证明通知、恢复、隔离与稳定性，不能用mock绿灯代替真语义。
**Independent Test**: 三组隔离进程/回环服务验证，不影响用户调试服务。

- [x] T027 [P] [US4] 新建 `oryxos-boot/src/test/java/com/oryxos/boot/MultiAgentIsolationTest.java`：两个差异Profile，测试模型捕获实际Tool schema，真实引擎/Session/SQLite；断言工具和会话隔离、A失败/阻塞时B成功、同Profile两规则互斥。（FR-005/012，SC-004）
- [x] T028 [P] [US4] 新建 `oryxos-boot/src/test/java/com/oryxos/boot/SchedulerStabilityTest.java`：真实cron触发/固定时区、失效MCP不阻断整机、短可注入受控截止与迟到阻塞夹具、60秒公共配置值守点；调用计数/锁/线程结束与可读错误必须可观察。（FR-001/013/015）
- [x] T029 [US4] 新建 `oryxos-boot/src/test/java/com/oryxos/boot/SchedulerFlowIT.java`（integration）：真实DeepSeek/本次北京天气/本地接收端，连续两次各3LLM+2Tool且会话一条、任务两历史；notify域名拒绝→失败审计→恢复许可后下一真实cron成功。来源/许可/真实条件记录到 `acceptance.md`。（FR-006/007/014/015，SC-003）
- [x] T030 [US4] 新建 `oryxos-boot/src/test/java/com/oryxos/boot/RestartRecoveryIT.java`（integration）及 `SchedulerFlowFixture.java` 子进程工具：以测试classpath启动实际main/serve，完成记录kill后同目录重启；另一例running落库后kill→unknown/零重放，核对停用和未来时间。（FR-002/003/008/011，SC-002）
- [x] T031 [US4] 在 `oryxos-boot/src/test/java/com/oryxos/boot/SchedulerFlowIT.java` 增一次真实60秒超时集成场景（受控慢模型/工具对端，不用真key等待故障）：504、取消、后续动作零发起、锁保持和后续健康任务可用；记录实测时间和退出证明。（FR-013，SC-006）
- [x] T032 [US4] 执行MultiAgentIsolationTest/SchedulerStabilityTest、显式SchedulerFlowIT/RestartRecoveryIT，核查真实审计/任务行数、时区、先红后绿证据与所有测试子进程关闭，记录 `specs/011-scheduled-task-management/acceptance.md`。（FR-005/006/011/012/013/014，SC-002/003/004/006）
- [x] T033 [US4] 在 `specs/011-scheduled-task-management/quickstart.md` 与 `acceptance.md` 记录Demo前置清单：天气精确域名、测试/企业通知区分、新闻源/MCP待提供条件、OpenAi自动配置排除；保留DR-003扩展Tool债务，不擅改用户 `.oryxos/` 或白名单。（FR-015）

## Phase 7: Polish & Cross-Cutting Concerns

- [x] T034 对 `specs/011-scheduled-task-management/spec.md`、`plan.md`、`tasks.md` 与实现执行逐故事一致性审查，补救任务只追加有来源的差异；记录报告于本 feature `acceptance.md`，保留每个故事与FR/SC的证据对应。（FR-001–015）
- [x] T035 核对 `oryxos-core`/`oryxos-storage`/`oryxos-web`/`oryxos-boot` 源码与测试的H4：IO白名单、两类审计、秘密不回显、Session ID单出处、虚拟执行模型、框架自动Tool禁用；实际匹配路径和必要例外写到 `acceptance.md`。（FR-006/015）
- [x] T036 对 `oryxos-web/src/main/resources/static/admin/` 最新构建及整个九模块运行 `mvn clean verify`，不跳过任何插件含OWASP；保存 `.verification/lesson28/verify.log` 和Surefire统计到 `acceptance.md`，不沿用旧441数量。（FR-001–015）
- [x] T037 使用 `oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar` 实际启动隔离实例，复核调度/四API/六页/SPA与重启入口，记录JAR摘要和子进程回收证明到 `acceptance.md`；继续保留用户原服务。（FR-009/010/011，SC-002/005）
- [x] T038 同步 `AGENTS.md`、`docs/TechnicalSolution.md`、`docs/DemandAnalysis.md`、`docs/AiProgrammingGuide.md`、`docs/IndustryResearch.md` 与 `README.md` 的实际完成状态/新增表和API范围；课程/admin Skill的计划页面只有实际验收后才能标已交付。（FR-001–015）
- [x] T039 执行最终实现一致性分析，在 `specs/011-scheduled-task-management/acceptance.md` 完成课件交付物/harness/H4/前序回归/真实及人工项证据；全部门禁通过才关闭任务，不删断言或新增全局抑制。（FR-001–015，SC-001–006）
- [x] T040 基于实际git status/diff输出改动点、重点review、验证命令及剩余人工项，更新 `specs/011-scheduled-task-management/tasks.md` 勾选与证据链接；不自动commit/push/package.sh。（FR-015）

## Dependencies & Execution Order

- Setup T001–002 → Foundational T003 → T004/T005 → US1 T006–010 → US2 T011–018 → US3 T019–026 → US4 T027–033 → Polish T034–040。
- T004需要T003类型；T005可与Store测试并行。T006→T007→T008→T009为同Store文件顺序。
- T013依赖T005；T012/T015修改同一Scheduler，必须串行。T016/T017对真实时序的期望改变必须明确是本节装配迁移，不删除chat门控等旧守点。
- T019/T020可独立写测试；T021后T022转绿；真实API可用后T023–025接前端。T020/T030共同使用SchedulerFlowFixture，后者必须在前者之后修改。
- T027/T028文件独立，可并行编写；T029/T031同文件串行。真IT失败先定位并追加明确补救，不偷偷放宽3+2计数。
- T036之前必须完成前端生产构建；T037验证新JAR。后续代码修复需重跑受影响测试与质量检查。
- 每个US完成时执行只读一致性审查，并把发现带到T034总核查；提交由用户决定。

## Parallel Examples

- US1：完成Store接口后，可并行编写T004存储测试和T005引擎测试；真正Store实现按文件串行。
- US2：默认串行改同一个Scheduler；ReActLoop内部T013可在T012之外独立实施，但T015合并前两者都需完成。
- US3：T019 Controller契约测试与T020无key整机测试；UI需真实接口完成后接入。
- US4：T027隔离与T028稳定性测试；真模型与进程重启各自使用独立工作区/端口，不共享运行状态。

## Implementation Strategy

MVP为US1：登记与持久化记录独立验证；US2增加自动/手动执行，US3提供用户操作面，US4补真实效果/恢复证明。各故事先测试后实现，保留旧合同，最后全量门禁。当前审批仅允许规格/计划/任务产出，本文件全部实现任务保持未勾选。

## 课件交付物映射

| 交付物 | 对应任务 |
|---|---|
| ScheduledTaskStore/两个View | T003 |
| 两表、实体、仓储、JpaScheduledTaskStore | T004、T006–010 |
| AgentScheduler登记/启停/历史/runNow | T011–018 |
| ScheduleApiController四端点+DTO | T019–022 |
| ScheduledTaskE2ETest | T020、T022 |
| SchedulerFlowIT/RestartRecoveryIT | T029–032 |
| 多Agent/稳定性 | T027–028、T031 |
| 定时管理页 | T023–025 |
| Demo条件/README | T026、T033 |
| 完整门禁/复审/报告 | T034–040 |

课件外必要内部改造：T013元数据/取消、T017启动时序/虚拟执行器、T022原整机扫描期望由四仓储加法到六仓储；均在plan列明，不改变旧AgentService/Memory/SessionManager公开签名。未增加public配置键或依赖。
