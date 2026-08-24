# Tasks: CLI 命令行入口与会话持久化

**Input**: Design documents from `/specs/003-cli-session/`

**Prerequisites**: plan.md、spec.md、research.md、data-model.md、contracts/cli-session.md

**Tests**: harness 先行——`SessionManagerTest`/`SessionRepositoryTest` 先于存储实现;命令行为属人工清单(课件明确不自动化)。

## Phase 1: Setup(类型与表就绪)

- [X] T001 [P] 建 oryxos-storage/src/main/java/com/oryxos/storage/session/SessionEntity.java:sessions 表 JPA 实体,字段照 data-model(session_id 主键、messages_json、context_state、status、三时间戳)
- [X] T002 [P] 建 oryxos-storage/src/main/java/com/oryxos/storage/session/SessionRepository.java:继承 JpaRepository
- [X] T003 扩展 oryxos-core/src/main/java/com/oryxos/core/session/SessionManager.java:加 `getOrCreate(String channel, String user, String profileName)` 与 `Optional<Session> get(String sessionId)`(17 节 save 不动);**删除 InMemorySessionManager.java**(17 节占位,17 节验收报告已预告本节升级,不留死代码)
- [X] T004 改造 oryxos-boot/src/main/java/com/oryxos/OryxOsApplication.java:显式加 `@EnableJpaRepositories("com.oryxos.storage")` + `@EntityScan("com.oryxos.storage")`(课件约定)

## Phase 2: Foundational(引擎装配,阻塞 chat/serve/gateway)

- [X] T005 建 oryxos-core/src/main/java/com/oryxos/core/config/CoreEngineConfiguration.java(@Configuration):装配 ProfileLoader(读 `.oryxos/profiles`,全局 provider 名集合经 Spring 按类型注入——`ProviderConfiguration` 加 `@Bean Set<String> globalProviderNames()`,core 用 `ObjectProvider<Set<String>>` 接收、缺省 `Set.of()`,无 Maven 依赖)/ProfileRegistry/ContextLoader(workspace `.oryxos`)/PromptBuilder/ToolExecutor(空工具表,20 节填)/ReActLoop/AgentService;未初始化工作区时 beans 给清晰报错「请先 oryxos init」

## Phase 3: User Story 1 - 会话按三元组幂等持久化 (P1)

**Independent Test**: 同三元组两次 getOrCreate 同 id;任一维度不同则不同;换全新上下文重查历史完整

- [X] T006 [US1] 先写 oryxos-storage/src/test/java/com/oryxos/storage/session/SessionManagerTest.java:**`sameTriple_alwaysSameSession`**(@DisplayName「同一三元组_历次getOrCreate都是同一个Session」,断言逻辑对齐课件:id 相等;换 channel 则不等)、user/profile 不同同样隔离、get 按 id 取回
- [X] T007 [US1] 先写 oryxos-storage/src/test/java/com/oryxos/storage/session/SessionRepositoryTest.java:SQLite 临时库执行真实 schema.sql 建表;存读往返;messages_json 含 user/assistant(带 toolCalls)/tool 三角色回读完整;**模拟重启**(新建 EntityManager 上下文重查)历史还在
- [X] T008 [US1] 建 oryxos-storage/src/main/java/com/oryxos/storage/session/JpaSessionManager.java(@Component 实现 SessionManager):id 拼接 `channel+":"+user+":"+profileName` 只在此一处;getOrCreate 查库→无则新建 active 落库;save 序列化 messages→messages_json;get 回读重建 Spring AI 消息(AssistantMessage.builder 带 toolCalls,已核实)

## Phase 4: User Story 2 - chat 交互入口 (P2)

**Independent Test**(人工):交互进入、多轮对话、/quit 退出

- [X] T009 [US2] 建 oryxos-channel-cli/src/main/java/com/oryxos/channel/cli/CliChannel.java:读 stdin → `AgentService.process` → 打印回复,`/quit` 退出;除此外无任何 Agent 逻辑
- [X] T010 [US2] 建 oryxos-cli/src/main/java/com/oryxos/cli/ChatCommand.java(@Command `chat`,`--profile` 默认 default):重命令,起 Spring 取 AgentService/SessionManager,`getOrCreate("cli", System.getProperty("user.name"), profileName)`(三元组 user 分量取本机账号)后委托 CliChannel

## Phase 5: User Story 3 - 12 命令与轻重分流 (P2)

**Independent Test**(人工):轻命令秒回;重命令启动日志仓储接口数 >0;12 命令 --help 正常

- [X] T011 [P] [US3] 建轻命令 StatusCommand.java(工作区/Profile 数/会话数)
- [X] T012 [P] [US3] 建轻命令 profile 组 oryxos-cli/src/main/java/com/oryxos/cli/profile/:ProfileListCommand/ProfileShowCommand/ProfileCreateCommand(建模板 YAML)/ProfileDeleteCommand(各 @Command,父命令 `profile`)
- [X] T013 [P] [US3] 建轻命令 ProviderListCommand.java(扫 profiles YAML 汇总 provider 名)与 ToolListCommand.java(列 `--profile` 声明的 tools);**实施期修正:三个 `list` 子命令撞名(Picocli DuplicateNameException,冒烟抓到),补 ProviderCommand/ToolCommand/SessionCommand 三个父命令承载**
- [X] T014 [P] [US3] 建轻命令 SessionListCommand.java(直连 `.oryxos/oryxos.db` JDBC 读 sessions 表列 id/profile/channel/status/last_active_at)
- [X] T015 [P] [US3] 建重命令 ServeCommand.java(@Command `serve`,`--port` 默认 8080,起 Web 运行时)与 GatewayCommand.java(起非 Web 守护骨架,日志说明核心阶段仅 CLI 通道);**实施期修正:两者均需 CountDownLatch 驻留主线程,否则命令返回后 OryxOsCli.main 的 System.exit 杀掉容器(冒烟抓到)**
- [X] T016 [US3] 改造 OryxOsCli.java:注册全部 12 子命令(profile/provider/tool/session 均挂父命令),usage 输出更新

## Phase 6: Polish & 收尾

- [X] T017 全量验证:`mvn clean verify -Ddependency-check.skip=true` 全绿;16/17 节测试全回归;`grep -rn "sk-" oryxos-*/src` 无明文;`grep -rnE "CompletableFuture|Mono<|Flux<" oryxos-cli oryxos-channel-cli oryxos-storage/src/main` 为空
- [X] T018 冒烟(人工引导项,不阻断):`java -jar oryxos-boot/target/*.jar init` → `profile list` 秒回 → `session list` 空表正常 → `chat --help` 正常

## Dependencies

- T001~T004(Setup)→ T008(存储实现);T005 依赖 T003/T008;T009/T010 依赖 T005;T011~T015 互相 [P];T016 最后收口
- Story 顺序:US1(地基)→ US2/US3(可并行,文件不重叠)

## Parallel Execution Examples

- Phase 1:T001/T002 可并行,T003/T004 串行(改既有文件)
- Phase 5:T011~T015 五个 [P] 命令类互不重叠,可并行

## Implementation Strategy

1. 存储地基先绿(T006~T008,harness 核心);
2. 装配与 chat 入口(T005、T009/T010);
3. 命令组收尾(T011~T016);
4. T017 硬门禁。

## 注意(语法禁区)

避开增强 switch 的 `default ->` 等 P3C/ASM 解析不了的 Java 18+ 语法形态;`mvn verify` 红即任务未完成。注释一律简体中文、只写"为什么"。
