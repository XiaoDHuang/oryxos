# Tasks: Agent Provider(大模型统一对接前台)

**Input**: Design documents from `/specs/001-agent-provider/`

**Prerequisites**: plan.md、spec.md、research.md、data-model.md、contracts/provider-api.md

**Tests**: 本特性按课件要求 harness 先行——每个 Story 内测试任务先于实现任务;测试方法名用英文,课件中文原名进 `@DisplayName`。

## Phase 1: Setup(配置与依赖)

- [X] T001 给 oryxos-provider/pom.xml 加 `org.springframework.ai:spring-ai-starter-model-openai`(不写版本,走 BOM),跑 `mvn -pl oryxos-provider -am dependency:tree` 确认解析成功
- [X] T002 改造 oryxos-boot/src/main/resources/application.yaml:删除 `spring.ai.openai.*` 直配段,新增 `oryxos.providers` 列表(deepseek/kimi 两项,api-key 用 `${DEEPSEEK_API_KEY}` / `${KIMI_API_KEY}` 占位,base-url 带默认值)
- [X] T003 改造 oryxos-boot/src/main/resources/db/schema.sql:`llm_calls` 增 `success INTEGER NOT NULL`、`error_message TEXT` 两列,保留既有 `status` 列

## Phase 2: Foundational(共享类型,阻塞所有 Story)

- [X] T004 [P] 建 Profile 记录类 oryxos-core/src/main/java/com/oryxos/core/profile/Profile.java:全字段(含 identity/provider/tools/skills/mcpServers/channels/notifyChannels/schedules/bootstrap/settings/createdAt/updatedAt,settings 默认值 maxIterations=10、maxHistoryTurns=20);YAML snake_case 键(mcp_servers 等)到 camelCase 字段的映射在 Loader 层做键名规整或用 SnakeYAML `TypeDescription` substitutes,不用 Jackson 注解
- [X] T005 [P] 建最小工具抽象 oryxos-core/src/main/java/com/oryxos/core/tool/OryxTool.java:接口仅 `getName()`/`getDescription()`/`getInputSchema()`(执行语义 20 节再补)
- [X] T006 [P] 建 Prompt 记录 oryxos-core/src/main/java/com/oryxos/core/prompt/Prompt.java:`messages` + `availableTools` 两字段,暴露 `getAvailableTools()`
- [X] T007 [P] 建 LlmCall 实体 oryxos-storage/src/main/java/com/oryxos/storage/audit/LlmCall.java:字段与 data-model.md 列一一对应(含 success/error_message)
- [X] T008 [P] 建 LlmCallRepository oryxos-storage/src/main/java/com/oryxos/storage/audit/LlmCallRepository.java:继承 JpaRepository
- [X] T009 建全局层配置 oryxos-provider/src/main/java/com/oryxos/provider/ProviderProperties.java(`@ConfigurationProperties("oryxos")` 承接 providers 列表)+ ProviderConfiguration.java:启动时逐条构建 `OpenAiChatModel` 放进 `Map<String, ChatModel>` Bean;api-key 解析后为空的条目抛「供应商 X 凭证缺失(期望环境变量 Y)」式错误、跳过该条不阻断其余(记 ERROR 日志)

## Phase 3: User Story 1 - 多供应商按名路由调用 (P1)

**Independent Test**: mock 两个 ChatModel,用指向 kimi 的 Profile 调一次,断言 kimi 被调 1 次、deepseek 零调用

- [X] T010 [US1] 先写 oryxos-provider/src/test/java/com/oryxos/provider/ProviderServiceTest.java 路由用例:`routesToNamedProvider_twoProvidersNoCrossTalk`(@DisplayName「按名路由_两个provider不串台」,断言逻辑对齐课件:verify(kimi, times(1)).call(any())、verify(deepseek, never()).call(any()))与 `unknownProvider_throws`(未知名抛 ProviderNotFoundException,消息含该名)
- [X] T011 [US1] 建 oryxos-provider/src/main/java/com/oryxos/provider/ProviderNotFoundException.java(消息带供应商名)+ ProviderService.java 骨架:`chat(String sessionId, Profile profile, Prompt prompt)` 从映射表按名取模型、取不到抛异常、发起 `ChatModel.call` 返回 `ChatResponse`(审计与工具先留空位,US3/US4 接线)

## Phase 4: User Story 2 - Profile 批量加载与合法性校验 (P2)

**Independent Test**: 同目录放合法全字段/语法损坏/引用未知供应商三份 YAML,断言合法的按名可查、坏文件有错误日志不阻断、未知供应商报错清晰;`${ENV}` 占位解析出环境变量真值

- [X] T012 [US2] 先写 oryxos-core/src/test/java/com/oryxos/core/profile/ProfileLoaderTest.java 四用例:合法 YAML 全字段解析、引用不存在 provider 报错清晰、坏文件不阻断其余加载、`${ENV}` 占位从环境变量解析
- [X] T013 [US2] 建 oryxos-core/src/main/java/com/oryxos/core/profile/ProfileLoader.java:扫 profiles 目录 *.yaml/*.yml,SnakeYAML 解析成 Profile,`${ENV_VAR}` 占位解析,坏文件 catch 记 ERROR 不阻断;校验规则只实施「provider.name 在全局供应商名集合中存在」
- [X] T014 [US2] 建 oryxos-core/src/main/java/com/oryxos/core/profile/ProfileRegistry.java:`Map<String, Profile>` 内存索引,`find(String name)` 返回 Optional;启动时由 Loader 灌入(运行时 register() 29 节再加)

## Phase 5: User Story 3 - 工具说明随调用下发但绝不执行 (P2)

**Independent Test**: 带一个 OryxTool 发起调用,captor 捕获请求,断言 options 的 internalToolExecutionEnabled=false 且工具 schema 已携带

- [X] T015 [US3] 先写 oryxos-provider/src/test/java/com/oryxos/provider/ToolSchemaAdapterTest.java:OryxTool → ToolDefinition 后 name/description/inputSchema 一一对齐;空入参返回空列表;产物类型不含执行逻辑(断言产物为 ToolDefinition 而非 ToolCallback)
- [X] T016 [US3] 建 oryxos-provider/src/main/java/com/oryxos/provider/ToolSchemaAdapter.java:`toSpringAiTools(List<OryxTool>)` 纯函数翻译成 `ToolDefinition`(DefaultToolDefinition.builder)
- [X] T017 [US3] ProviderService 接线:chat 内调 adapter,构造 options 时 `internalToolExecutionEnabled(false)`;在 ProviderServiceTest 补关键回归 `callWithToolSchema_disablesAutoExecution`(@DisplayName「带工具schema调用_请求里关闭了自动执行」,ArgumentCaptor 断言 autoExecute=false 且 tools 非空)

## Phase 6: User Story 4 - 调用无论成败都留审计痕迹 (P3)

**Independent Test**: 成功/失败各一次,断言 llm_calls 各增一条,失败记录 success=false 且 error_message 含原因、异常仍上抛

- [X] T018 [US4] 先写 oryxos-storage/src/test/java/com/oryxos/storage/audit/LlmCallRepositoryTest.java:用 SQLite 临时库(`jdbc:sqlite:` 临时文件,不用 H2、不用 Hibernate 自动建表)执行真实 `db/schema.sql` 建表,存读往返,断言 success/error_message 两列真实可写读
- [X] T019 [US4] 建审计写入组件 oryxos-provider/src/main/java/com/oryxos/provider/LlmCallAudit.java(注入 LlmCallRepository):`record(sessionId, provider, model, usage, success, errorMessage, latencyMs)`,自身失败 catch 记 ERROR 不阻断主流程
- [X] T020 [US4] ProviderService 接线:成功路径落 success=true+usage+latency;catch(RuntimeException) 落 success=false+e.getMessage() 后原样上抛;在 ProviderServiceTest 补关键回归 `callFails_auditSavedBeforeRethrow`(@DisplayName「调用失败_审计必须留下success为false的记录」,断言抛异常且 audit 先落账:verify(audit).record(eq("s-1"), eq("deepseek"), any(), isNull(), eq(false), contains("timeout"), anyLong()))

## Phase 7: Polish & 收尾

- [X] T021 建 oryxos-provider/src/test/java/com/oryxos/provider/ProviderSmokeIT.java:`@Tag("integration")`,读 `DEEPSEEK_API_KEY` 真调一次,断言非空响应 + llm_calls 多一条 success=true;未设 key 时 Assumptions 跳过
- [X] T022 全量验证:`mvn clean verify -Ddependency-check.skip=true` 全绿;`mvn test` 全部前序测试(oryxos-web 既有 4 个测试类等)回归绿;`grep -rn "sk-" oryxos-*/src` 无明文 key

## Dependencies

- Phase 1 → Phase 2 → Story 各 Phase;Story 间顺序:US1(路由骨架)→ US2(Profile,与 US1 弱耦合可并行,但 Profile 类型在 Phase 2 已就位)→ US3(接入 chat)→ US4(接入 chat)。US3/US4 都改 ProviderService,须串行。
- 关键路径:T001→T009→T011;T003→T007/T008→T018→T019→T020

## Parallel Execution Examples

- Phase 2 内:T004/T005/T006/T007/T008 五个 [P] 任务文件互不重叠,可并行
- Phase 3 与 Phase 4 可并行(不同模块:provider vs core)
- T021 与 T022 之前的所有任务完成度无关的部分不并行——冒烟依赖全部实现完成

## Implementation Strategy

1. 先 Setup + Foundational(T001–T009),保证类型与配置就位;
2. US1 打通路由最小闭环(MVP);
3. US2/US3/US4 依次接入;每个任务完成即跑该模块测试,红了当场修;
4. T022 作为本节 DoD 硬门禁。

## 注意(语法禁区)

- 全程避开增强 switch 的 `default ->` 等 P3C/ASM 解析不了的 Java 18+ 语法形态;静态检查是构建门禁,`mvn verify` 红即任务未完成。
