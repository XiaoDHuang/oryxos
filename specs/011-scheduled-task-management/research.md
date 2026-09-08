# Research: 011 定时任务管理

日期：2026-09-07。研究为只读本地依赖/代码核实，不是运行验收。

## R1 — 版本与 API

- Decision：沿用 Spec Kit 0.14.2、Boot 3.5.16 / Spring 6.2.19、Spring AI 1.1.8、Hibernate 6.6.53.Final、SQLite JDBC 3.53.2.1，无新增依赖。
- Evidence：`.specify/init-options.json`；根 POM；本轮 `mvn -pl oryxos-core,oryxos-storage -am dependency:tree '-Dincludes=org.springframework:*,org.springframework.ai:*,org.hibernate.orm:*,org.xerial:sqlite-jdbc'` BUILD SUCCESS（3.413s）。现有 memory POM 的 maven-jar-plugin 未锁版本警告原样记录，不在本轮升级插件。
- 本地 JDK 21.0.11 的 javap 核实 SimpleAsyncTaskExecutor.setVirtualThreads/setTaskTerminationTimeout/setCancelRemainingTasksOnClose/submit/close，CronTrigger(String,ZoneId)/nextExecution，ToolResponseMessage.Builder.metadata；研究子任务另核实 ThreadPoolTaskScheduler.schedule(Runnable,Instant)。方法来自本地 6.2.19/1.1.8 JAR。
- Alternatives：新调度库/另一套 AI BOM 无必要；只给默认单槽 scheduler 设置虚拟线程仍会阻塞另一任务，不能解决隔离。

## R2 — 结果判定不改变核心公开入口

- Decision：ReActLoop 给本轮 ToolResponse 加内部成功元数据，Scheduler 检查当前 Session 新增消息及无 Tool 最终答复。
- Rationale：AgentService.process 仅返回 String；Tool 失败会回填模型，耗尽轮数也返回固定 String。把正常返回当成功或解析回复文本均不可靠。
- Evidence：`oryxos-core/.../react/ReActLoop.java`、`AgentService.java`、`session/Session.java`。Session 只追加，窗口裁剪发生于 prompt 构建；`oryxos-storage/.../session/JpaSessionManager.java` 不保存 metadata，因此判定只读执行中的同一对象。
- Alternatives：新增 ProcessOutcome 公共类型/修改 process 签名扩大核心契约；按 sessionId+时间查审计可能混入并发调用，均不采用。
- Limit：ToolExecutor 当前审计异常固定日志并返回工具结果，保留其既有失败策略；验收须单独核对审计。新任务 Store 的事务异常不得吞掉。

## R3 — 时钟和同步执行分开

- Decision：Spring 托管 SimpleAsyncTaskExecutor 的虚拟 worker 执行；既有 ThreadPoolTaskScheduler 派发与取消；任务锁+Profile 锁由 worker 自取自放。
- Evidence：CoreEngineConfiguration 默认没有 poolSize 配置，AgentScheduler 直接在回调中 process；不同 Profile 会被长任务阻塞。同 Profile 的所有规则使用同一 scheduler Session，需要额外 Profile 维度互斥。
- Rationale：不建固定线程池或异步编排框架；CountDownLatch/私有状态用于等待响应与截止，不改变同步引擎模型。
- Alternatives：仅增大固定池没有有界并发隔离证明；在 Future.cancel 后马上释放锁可能造成被取消代码仍运行时重叠。

## R4 — 开始先记账与条件终态

- Decision：begin 原子插入 running 历史并加一次 run_count；finish 条件更新未完成行+任务状态，不二次加次数。失败/超时和正常 worker 竞争只能产生一个终态。
- Rationale：只在 finally 插历史会丢掉进程被 kill 的执行；先记开始才能在重启时诚实标记 unknown。任务执行涉及外部副作用，不能自动重放。
- Evidence：现有 SQLite schema 是幂等 CREATE TABLE，boot `spring.sql.init.mode=always`、`defer-datasource-initialization=true`。因此新 Store 的启动访问应在数据库初始化后，不照搬 Bean 创建期间直接 registerAll 的旧时序。
- Alternatives：使用 hibernate update 不符合宪法；跨模型调用长事务占住 SQLite 写锁；改成外部队列扩大范围。

## R5 — 定义、历史与当前可运行性

- Decision：全局 task id 保持 009 语义；DB 保存定义快照与历史，当前 catalog 代表本次启动合法规则。列表 DTO 的 available 是派生值，不增持久化列。缺失规则留历史但不可执行。
- Rationale：自动删除会抹审计，启动覆盖 enabled 会让用户停用失效；同 id 换 Profile 会污染归属，拒绝并记录。
- Alternatives：复合 id 会改变 009 的字面量契约；Profile/Skill 双定义源违背 D28-03。

## R6 — 管理接口和 UI

- Decision：四 API、嵌套 DTO；history page=0/size=20，size≤100；未知404、非法/不可运行/忙碌400；已记录业务失败返回执行视图 success=false，超时504，基础设施未能记账500。没有任务定义创建/删除/编辑。
- Evidence：既有 SessionApiController、ApiResponse/ErrorCode/GlobalExceptionHandler、frontend api.js/main.js/App.vue；PUT 严格只接 enabled，不静默接收 cron/message。
- Rationale：操作成功提交与业务执行成功须分别展示，前端不能因 200 就显示任务成功。未知结果不自动重放 POST。
- Alternatives：新增全局异常框架/认证或通用 CRUD 超出范围。网站风格和新定时页例外已获批准。

## R7 — Harness 分层

- Decision：ScheduledTaskE2ETest / MultiAgentIsolationTest / SchedulerStabilityTest 默认无 key；SchedulerFlowIT 真 DeepSeek、公开天气、回环 webhook；RestartRecoveryIT 可用 mock 保持重启可重复，并在最终 JAR 故事中核查同一入口。IT 显式 integration，不依赖默认 Surefire 名称匹配。
- Rationale：机制回归无需真实模型付费；真实语义由单独真模型测试证明。3 LLM/2 Tool 是用户批准的固定成功脚本，不为模型偶发额外调用放宽断言。
- Evidence：27 的 MockChatModel、HumanFlowFixture、HumanTriggerFlowIT、MockAgentE2ETest；本轮复用生产 wiring 和临时路径，不复用用户工作区。
- Alternatives：所有场景只 mock AgentService 测不到装配；只调用 runNow 测不到真实 cron；在同一上下文重建 Bean 不等于进程重启。

## Clarify 覆盖结论

正式新增提问 0 项；既有 D28 决议覆盖产品范围冲突。身份/生命周期、交互错误、并发、超时、保留策略、外部依赖与完成条件均已写成 spec 的显式默认值，并由本计划与任务审阅。安全沿用现有内网/白名单/环境变量约束；不引入新治理能力。没有未解析技术占位符。企业通知/新闻 MCP 具体地址是后续部署资料，不阻塞本节隔离设计，但阻塞相应真实企业 Demo 的验收声明。

## 文档验证记录

- `.specify/extensions.yml` 不存在，无前后置扩展 hook；模板解析使用项目 `.specify/templates`。
- check-prerequisites（PathsOnly）、setup-plan、setup-tasks、含RequireTasks的前置检查均成功定位011；脚本JSON的BRANCH字段使用特性目录名，实际Git分支以git branch --show-current为准，是028-lesson28-scheduler-management。
- tasks共40项，编号唯一且连续；US1=5、US2=8、US3=8、US4=7、共享=12，均未勾选。15条FR与6条SC都有明确关联任务；规格checklist16/16是文档检查，不是功能测试。
- git diff --check通过。两个Skill均为小幅范围路由/管理页例外更新；尝试内置quick_validate.py时系统Python与随附Python都缺少PyYAML，未执行成功，未安装全局依赖。已人工核对frontmatter、既有元数据、28路由及引用。不可把人工核对写成官方校验器通过。
- 本轮仅运行dependency:tree与本地javap及文档诊断，未运行功能测试/完整verify、未访问真实模型、未修改业务代码或用户运行环境。
