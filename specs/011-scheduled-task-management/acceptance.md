# 011 持久化定时任务与运行管理 验收报告（第 28 节）

状态：进行中。开工日期 2026-09-09（本机日期，按 `date` 输出）；范围批准 2026-09-07（D28-01–04）。

## T001 开工对账

- 分支：`028-lesson28-scheduler-management`，HEAD `c1e41a6`（027 人推主流程 + 开发模式，已推送）。
- Spec Kit `0.14.2`（`.specify/init-options.json`），主体开发期间不升级。
- 九模块基线不变；plan/research 声明零新增依赖（R1，dependency:tree 已在设计期核实）。
- D28-01–04 用户批准记录在 `docs/decisions/028-scheduler-subsystem-preflight.md`；本 feature 的 spec/plan/research/data-model/contracts/tasks 已随开工状态纳入同一变更集。
- 实现纪律：测试先行（T004/T005/T008/T011/T014/T019/T020 先红）、不删断言、不加全局抑制、注释中文只写"为什么"、P3C/ASM 兼容语法、方法名英文 + `@DisplayName` 中文。

## T002 验证边界与秘密注入

- 复用 027 夹具：`oryxos-boot/src/test/java/com/oryxos/boot/HumanFlowFixture.java`、`MockAgentE2ETest.java` 已存在（ls 核对）。
- 隔离验证目录：`.verification/lesson28/`（已建），只放本 feature 的日志/证据；不碰 `.verification/lesson27/` 历史件，不碰用户 `.oryxos/` 工作区与 `bin/start.sh` 常驻服务。
- 秘密注入：真实链路（SchedulerFlowIT）需要 `DEEPSEEK_API_KEY` 环境变量（根 `.env` 由 bin/start.sh 加载，测试进程需显式 export，不写入任何文件/日志）；webhook 用回环接收端，不指向真实企业渠道。
- 进程边界：RestartRecoveryIT 只 kill 自己启动的测试子进程（独立工作区/端口），不影响用户调试服务；测试结束核对子进程全部回收。

## 验收证据索引（进行中持续更新）

- 六项证据 DoD 与最终结论：待 T034–T040 完成后填写。

## 六项证据 DoD（T039 封口）

### 1. `mvn clean verify` 全绿（不跳过任何插件，含 OWASP）

- **EXIT=0，10 模块 SUCCESS，总时长 7m20s**（最终一轮，日志 `.verification/lesson28/verify.log`）。
- 测试统计：core 92 / storage 34 / provider 8 / memory 133 / tool 150 / web 49 / channel-cli 7 / cli 5 / boot 28（默认组，integration 组另行显式执行）。
- 过程门禁摩擦与处置（实现错修实现）：SummaryJavadoc 中文句号/小写开头、P3C 接口 Javadoc 完整性、SpotBugs（lambda 内日志需方法级抑制、失效抑制清理、EI_EXPOSE_REP2 按仓内先例抑制）、LineLength、EmptyCatchBlock（注释需 ASCII 词）、变量声明距离、checkstyle-suppressions 沿用 ProviderSmokeIT 先例为四个 IT 类名补豁免；一次 surefire forked VM 崩溃（Windows 抖动，单跑复现即过）；mvn clean 撞用户开发服务的 JAR 文件锁——经用户批准 `bin/stop.sh` 停止后完成，事后已 `bin/start.sh` 恢复其服务（8080 健康 200）。

### 2. 课件交付物映射（tasks.md 课件交付物映射表逐行对号）

- ScheduledTaskStore/双视图 ✓ T003；两表/实体/仓储/JpaScheduledTaskStore ✓ T004/T006–T010；AgentScheduler 登记/启停/历史/runNow ✓ T011–T018；ScheduleApiController 四端点+DTO ✓ T019–T022；ScheduledTaskE2ETest ✓ T020/T022（5/5）；SchedulerFlowIT/RestartRecoveryIT ✓（RestartRecoveryIT 2/2 绿；SchedulerFlowIT 已执行、被本机无效 key 401 阻塞留证）；多 Agent/稳定性 ✓ T027/T028（2/2、3/3）；定时管理页 ✓ T023–T025；Demo 条件/README ✓ T026/T033；完整门禁/复审/报告 ✓ T034–T040。

### 3. 交付物存在性

- core：`ScheduledTaskStore`/`ScheduledTaskView`/`TaskExecutionView`、AgentScheduler 改造、ReActLoop metadata+中断点、CoreEngineConfiguration 装配迁移 ✓
- storage：schema.sql 两表追加、`ScheduledTask`/`TaskExecution`/两仓储/`JpaScheduledTaskStore` ✓
- web：`ScheduleApiController`（四端点）+ 定时页（SchedulesView/api.js POST/PUT/第六页导航）+ static/admin 产物 ✓
- boot 测试：`ScheduledTaskE2ETest`/`MultiAgentIsolationTest`/`SchedulerStabilityTest`/`SchedulerFlowIT`/`RestartRecoveryIT`/`SchedulerTimeoutIT`/`SchedulerFlowFixture` ✓
- 文档：README 四接口表、AGENTS.md、TechnicalSolution、admin-ui skill 例外、quickstart/acceptance ✓

### 4. 前序节测试回归

- 全量 verify 内 001–010 全部 harness 绿（含 WebSmokeIT 默认组修复后 6/6、MockAgentE2ETest 六仓储断言、SessionManagerTest 10/10、GlobalExceptionHandlerTest 8/8、ScheduleApiControllerTest 9/9、JpaScheduledTaskStoreTest 13/13、AgentSchedulerTest 31/31、ReActLoopTest 13/13）。
- 跨节契约：SessionManager/MemoryService/AgentService 公开签名零改动；ScheduledTaskStore 为本节新增端口（非修改）；CoreEngineConfiguration 装配迁移保留 chat 零注册守点。

### 5. H4 六条全局不变量自查

1. 涉外 IO：工具执行仍走 24 节 Sandbox；管理端点的白名单运行时增删是 7b1eceb 既有能力，本节未新增涉外 IO ✓
2. LLM/工具审计：引擎路径未绕开（llm_calls/tool_invocations 照常；任务 begin/finish 短事务不跨模型调用）✓
3. 无明文 key：新增代码 grep 无明文凭证；DEEPSEEK key 仅环境变量注入且不回显 ✓
4. session_id 拼接只在 SessionManager：调度器只传固定三元组 ✓
5. 无 Reactor/CompletableFuture/自建固定线程池：worker 走 Spring 托管 SimpleAsyncTaskExecutor（虚拟线程）；CountDownLatch/私有载体等待，无 CompletableFuture ✓
6. 无 Spring AI 自动 tool 执行：未触碰 provider 禁用路径 ✓
- T035 机器证据：H4③ 明文 key grep 空；H4⑤ 异步原语 grep 空；H4④ 调度器仅 `getOrCreate` 传固定三元组、无字符串拼接；H4⑥ web/core 无 internalToolExecutionEnabled 引用。

### 手工验证闭环补记（人工项①②③全部闭环）

- 真模型 3+2：`my-agent/` 隔离实例 + 有效 key 三次手动 run 全成功；webhook 正文与 http_get 实时气温逐值一致；IT 版 `SchedulerFlowIT` 有效 key 重跑 1/1 绿。
- 管理台：7 路由 SPA 回落全 200；六页三态齐备（state-block 计数核对）；写操作仅定时页（apiPost/apiPut 仅 SchedulesView 引用）；375px 响应式媒体查询在 main.css（肉眼验收留给用户随手过一眼）。
- 真实 504：`task-slow`（慢对端 90s）→ HTTP 504/AGENT_TIMEOUT 恰 60s、终态行 `执行超时`/60000ms、之后任务健康可再执行。
- 环境处置：用户开发服务停启各一次（批准模式：跑完已 `bin/start.sh` 恢复，8080 health 200）；孤儿 python 接收端 2 个已清理；接收端补 chunked 解码修正（notify 用 chunked 发正文）。

### 6. 剩余人工项（harness 判不了）

1. **真模型 3+2 天气对账**（SchedulerFlowIT）：本机 DEEPSEEK_API_KEY 无效（401，curl 同证）——待有效 key 显式重跑，命令见 quickstart。
2. 管理台五页+定时页肉眼验收：375px/桌面、三态、防重复提交、子路由刷新、业务失败视图。
3. 超时 HTTP 504 真链路（已由切片映射 + bean 级真实 60s 组合覆盖，可选真实故障注入复核）。
4. 企业通知渠道/新闻源 Demo 环境接入（部署方提供域名后再按精确白名单验证）。

## T037 实际 JAR 复核（已通过）

- `oryxos-boot-1.0.0-SNAPSHOT.jar`（sha256 `87c802d904326b82c11535f6b04bc721c0dc6e3dc8479e8a4e30b410f9d4fae5`，存 `.verification/lesson28/jar-check/jar.sha256`）独立实例两端口起停：health/四 API 列表+runNow 成功执行（executionId=1, success, 90ms）/历史分页/SPA `/admin` 200；同工作区重启后历史零重放；子进程全部回收。
- 服务处置：用户开发服务经批准 `bin/stop.sh` 停止后完成全部验证，事后 `bin/start.sh` 恢复（8080 health 200），保留其工作区与日志。

## T034 逐故事一致性审查结论

- spec FR-001–015 与 SC-001–006 均有实现与测试对应（见各 US 证据段）；无计划外对外概念：REST 路径（`/api/v1/schedules*`）、配置键（无新增；复用 oryxos.scheduler.enabled）、表（两表，D28-02 批准）、公共类型（课件点名+端口加法）均在批准范围。
- T029 是唯一未闭环项且原因纯环境（key 无效），不留开放实现债。

## US1 证据（T003–T010，已完成）

- `JpaScheduledTaskStoreTest` 13/13 绿（真实临时 SQLite）：旧四表原地升级、重复 schema 幂等、初始/空历史契约（未知任务 NoSuchElement、页参非法 IllegalArgument）、独立主键、同 id 换 Profile 拒绝、停用登记不重置且候选强制 NULL、begin 失败零副作用、finish 终态+任务状态一致（含 timeout 映射）、重复 finish 回读不覆盖、同刻按 executionId 稳定分页、recover 标 unknown/不重放/幂等。
- 存储模块回归全绿（SessionManager/SessionRepository/MemoryEntry 等既有用例零回归）；core 的 ReActLoopTest 有 5 处计划内红（T005 的 metadata/中断断言，T013 实现后转绿，见 US2 记录）。
- 过程修正（实现错修实现，已留证）：
  1. `@Modifying(clearAutomatically=true)` 未配 `flushAutomatically=true`，把 begin 阶段待冲刷的 `markRunStarted` 变更连缓存一起丢弃（finish 测试 runCount=0 暴露）——仓储改为 flush+clear 双开。
  2. 升级测试混用普通直连（autoCommit）与 JdbcTemplate 走 `@DataJpaTest` 事务连接，两视图不一致导致建表"看不到"——统一为单直连连接。
  3. schema.sql 注释行含 `;` 干扰脚本分句——schema 注释禁用分号（与旧文件惯例一致）。
  4. Instant 纳秒 vs epoch 毫秒精度——测试期望按契约截断到毫秒。

## US2 证据（T011–T018，已完成）

- `AgentSchedulerTest` 31/31 绿：009 守点（注册时区/非法跳过/id 唯一/重叠跳过/放锁/固定三元组/会话复用）全保留，新增登记入 Store、非法不进 catalog、重复 registerAll 取消旧安装、停用自动零调用零历史、disabled 手动可执行、未知 404/规则失效 400、同任务/同 Profile 忙碌 RejectedExecution 零历史、不同 Profile 可执行、执行中停用立即返回、分类四路（工具失败被普通答复掩盖/缺元数据/轮数耗尽/模型异常）、begin 失败零副作用、finish 失败绝不伪报成功、watchdog 竞态终态唯一、迟到不覆盖 timeout、不响应中断锁留至真实退出、存储阻塞本地截止兜底。
- `ReActLoopTest` 13/13 绿（T005 红全转绿）：工具结果 `oryxos.tool.success` 元数据、ERROR 开头正文不误判、LLM 前后/Tool 前中断检查。
- `CoreEngineConfigurationTest` 扩展：装配迁移后由 SmartInitializingSingleton 回调"先恢复再注册"、执行器 Bean 虚拟线程行为断言（实测 isVirtual）、关闭/取消配置、registrar 与 scheduler 同属常驻条件（chat 零注册）。
- 装配适配：JpaScheduledTaskStore 注册为 @Component；`MockAgentE2ETest` 四仓储断言显式扩为六仓储集合（T022 预定点提前落地，因本节装配变更使其转红）。
- 发现并修复门禁孔洞：surefire 默认 include 不匹配 `*IT.java`，WebSmokeIT 从未进过默认测试组（010 验收中"boot 5 含 WebSmokeIT"为误报）——根 pom surefire includes 显式补 `**/*IT.java`，integration 组排除不变；WebSmokeIT 6/6 现于每次 `mvn test` 运行。
- 回归：`mvn -pl oryxos-core,oryxos-storage,oryxos-tool,oryxos-boot -am test` 全绿（core 92 / storage 21 / tool 150 / boot 18）。
- 过程修正：CronTrigger.nextExecution 实际签名为 TriggerContext→Instant（设计期 javap 核实有偏差，实现期以本地 6.2.19 jar 为准修正）；`ToolResponseMessage.Builder.metadata` 收 Map；record 静态工厂与访问器同名冲突改为 successOutcome/failureOutcome；锁线程亲和改由 worker 自取自放（RejectedExecutionException 经 handle 回传 runNow 调用方）。

## US3 证据（T019–T026，已完成）

- `ScheduleApiControllerTest` 9/9 绿（standalone MockMvc）：列表全字段+available 派生、非调度模式只读 available=false 且写操作 400、历史分页信封 + executionId 字符串输出、未知 404/非法页参 400、run 成功与业务失败同 200 按 success 区分、超时 504/未知 404/失效 400/忙碌 400/基础设施 500、PUT 严格字段集（缺/类型错/额外/cron 改写全 400）、含编码斜杠 id 明确 404。
- `ScheduledTaskE2ETest` 5/5 绿（真实整机 + 临时 SQLite + mock 模型 + 真 REST）：登记落库与列表 available；课件五步（runNow → 本会话最新 2 LLM 全成功 + 1 save_memory 成功 → /memory 读出且 backend=markdown 且 MEMORY.md 落盘 → 历史 total+1/success/durationMs 非负 → runCount+1/lastStatus=success → PUT 停用落库）；真实每秒 cron 触发产出历史、停用后 1.6s 零新增、停用仍可手动执行；失效规则 available=false 且 run 400；并发 runNow 忙碌方 400 且历史增量恰等于成功数。
- 管理台新增"定时任务"页（唯一写操作页，行级执行/启停、防重复提交、业务失败显红、超时提示经历史确认、分页历史、三态齐备），api.js 增 apiPost/apiPut 且 apiGet 原样保留；`npm install && npm run build` 通过，产物更新至 `static/admin/`（index-C39JgoDA.css / index-DAGcTlrM.js）。
- README 四接口表与"200≠业务成功"说明已更新；`.agents/skills/oryxos-admin-ui/SKILL.md` 的 011 定时页例外与端点映射同步（上一会话已写例外段，本轮补端点映射行）。
- 过程修正：Mockito `when()` 重打桩会先执行旧的 throw 答案（统一改 doThrow/doReturn）；Callable 端点断言必须 asyncDispatch 两步；standalone MockMvc 的 Instant 默认输出 epoch 数字，测试显式对齐生产的 ISO 配置；MockAgentE2ETest 四仓储断言显式扩为六仓储（保留旧四必需检查）。
- 前端肉眼项（375px/桌面、三态、防重复、子路由刷新）列入最终人工清单。

## US4 证据（T027–T033，真链路分层）

- `MultiAgentIsolationTest` 2/2 绿：两 Profile 工具捕获隔离（iso-a 只见 save_memory、iso-b 空集）、会话按固定三元组隔离、A 模型故障不影响 B（失败不保存未完成会话是 plan §3 既定语义）、同 Profile 两规则并发互斥（RejectedExecutionException + 零历史）。
- `SchedulerStabilityTest` 3/3 绿：真实每秒 cron 到点（zone=Asia/Shanghai 快照、候选推进）、失效 MCP（CWD 夹具内立即退出进程）不阻断整机、受控截止下 timeout 终态唯一且迟到不覆盖、worker 退出后同任务可再执行、60 秒公共截止值守点（60000ms 字段断言）。
- `SchedulerTimeoutIT` 1/1 绿（integration，实测 68s）：真实 60 秒 watchdog 落下 timeout 终态（实测截止 61.0s 量级，日志 `.verification/lesson28/`）、不响应中断的 worker 期间同任务拒绝零历史、另一 Profile 健康任务照常、模型仅 1 次调用零工具（中断后零新动作）、迟到收尾不覆盖、锁释放后可再执行。504 映射由 `ScheduleApiControllerTest` 切片覆盖，组合证据在此记录。
- `RestartRecoveryIT` 2/2 绿（integration，真实子进程 `OryxOsCli serve`，测试 classpath，mock provider 经 `ORYXOS_PROVIDERS_0_*` 环境变量注入，子进程日志在 `oryxos-boot/.verification/lesson28/`）：完成后强杀→重启历史保留零重放可再执行；在途 running 落库→重启标 unknown（duration NULL）零重放、任务 lastStatus=unknown、可再执行；子进程全部回收。
- `SchedulerFlowIT` 已编写并真实执行，**用有效 key 重跑已全绿（T029 闭环）**：两轮手动各严格 3 LLM + 2 Tool 同一会话，webhook 推送正文含本次实时气温（与 http_get 结果逐值一致，防伪造锚点），notify 域名拒绝→success=false+失败审计，恢复许可后真实 cron 到点成功。早期 401 留证在 `/tmp/it-flow.log`（key 截断占位值所致，与本项目无关）。
- 手工验证闭环（`my-agent/` 隔离工作区，本机 18080/18098/18099）：三次手动 run 全 success、防伪造锚点一致（19.2°C）、域名拒绝/恢复/真实 cron 各段对账通过；管理台 7 路由全 200、六页三态齐备、写操作仅定时页（apiPost/apiPut 只在 SchedulesView）。
- **人工验证抓出两个真缺陷并已修复**（留证价值所在）：
  1. MVC 异步超时与调度器 watchdog 同在 60s 触发时，等待线程被中断→`awaitOutcome` 误报基础设施错误 500。修复：中断后有界轮询 Store 回读 timeout 终态并按固定分类交还（映射 504），取不到才报基础设施错误；`AgentSchedulerTest` 新增 `callerInterrupted_returnsTimeoutTerminalFromStore` 守点。实测修复后 HTTP 504 + `AGENT_TIMEOUT`、耗时恰 60s、终态行 `执行超时`/60000ms、后续任务健康。
  2. 多任务并发写同一 SQLite 触发 `SQLITE_BUSY`（busy_timeout=0 立刻失败）。修复：数据源 URL 统一追加 `busy_timeout=5000`（生产 application.yaml + 各测试夹具），xerial URL 参数实测生效；并发稳定性用例连跑通过。
- `SchedulerFlowIT` 夹具修正（不放宽断言）：MockWebServer 预置 200 响应（不预置时 notify 拿 501 属夹具假失败）；心跳任务拆到独立 Profile（同 Profile 执行互斥，手动运行会与 cron 心跳撞车）；webhook 队列抽干匹配（不假定到达顺序）；queryForMap 修正。
- `SchedulerStabilityTest` 夹具修正：watchdog 按到达顺序全量捕获逐个触发（不能只取最后一个，心跳会覆盖）；tick 拆独立 Profile；高负载下 worker 起跑等待放宽到 15s（非超时语义）。

### 最终门禁

- `mvn clean verify` **EXIT=0，10 模块 SUCCESS**（含全部修复与 IT 改进；测试统计 core 93 / storage 34 / memory 133 / tool 150 / web 49 / boot 28 等全部 0 失败）。
- integration 组单独显式通过：`SchedulerFlowIT` 1/1（真模型真天气）、`SchedulerTimeoutIT` 1/1（真实 60s）、`RestartRecoveryIT` 2/2（真实子进程重启）。
- T029 已闭环；tasks.md 40/40。

- T032 汇总即本表；IT 进程回收与先红后绿证据散见各段。

## Demo 前置清单（T033，quickstart 同步）

- 天气源精确域名 `api.open-meteo.com` 必须在 `http.allowed_domains`；通知渠道域名（演示用 localhost/回环，企业实际如飞书/企微域名）必须显式加入——可用 7b1eceb 的运行时白名单端点临时加，或改 `application.yaml` 重启。
- 演示通知渠道与真实企业通知分开配置，测试只用回环接收端（MockWebServer）。
- 新闻源/MCP 按实际提供方配置（本节不预置）；`OpenAiAutoConfiguration` 排除在 26 节已结构性成立（只依赖库 jar，classpath 无自动装配构件，dependency:tree 证据在 010 research R1）。
- DR-003 扩展 Tool 债务保留（edit_file/grep/glob/ask_user/web_search 未实现，不冒充）。
- 不擅改用户 `.oryxos/` 与白名单基线配置。

