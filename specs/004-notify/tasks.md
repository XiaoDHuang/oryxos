# Tasks: 主动通知出口

**Input**: Design documents from `/specs/004-notify/`

**Prerequisites**: plan.md、spec.md、research.md、data-model.md、contracts/notify-contract.md、quickstart.md

**Tests**: harness 先行。`WebhookNotifyAdapterTest` 覆盖本节第一批全部自动化验收；测试方法名英文，
课件原文用 `@DisplayName` 保留。`NotifyToolsTest` 明确延期到第 20/24 节，不在本 feature 创建空壳。

## Phase 1: Setup（依赖锁定）

- [X] T001 在 pom.xml 新增并锁定 `mockwebserver.version=4.12.0`,将 `com.squareup.okhttp3:mockwebserver` 加入 dependencyManagement
- [X] T002 在 oryxos-tool/pom.xml 增加 `org.springframework:spring-web` 编译依赖与 `com.squareup.okhttp3:mockwebserver` test 依赖,运行 dependency:tree 确认 Spring Web 6.2.19/MockWebServer 4.12.0

## Phase 2: Foundational（中立通知契约）

**Purpose**: 提供所有通知场景共享的最小契约；完成前不得实现 webhook 发送。

- [X] T003 [P] 创建 oryxos-tool/src/main/java/com/oryxos/tool/notify/NotifyChannelAdapter.java,逐字保留 `void send(NotifyTarget target, String content)` 签名
- [X] T004 [P] 创建 oryxos-tool/src/main/java/com/oryxos/tool/notify/NotifyTarget.java,实现 `channelType` + `config` record、channelType/config 非 null 校验与 config 防御性复制；允许空 config,专属必填键由 Adapter 校验
- [X] T005 核对 oryxos-core/src/main/java/com/oryxos/core/profile/Profile.java 与 oryxos-core/src/test/java/com/oryxos/core/profile/ProfileLoaderTest.java 已交付 `notifyChannels`/`notify_channels`(type + url)并保持原契约,不新增第二个配置字段

**Checkpoint**: 通知调用方只依赖中立契约，未出现企业微信/飞书等平台专属 public 类型。

## Phase 3: User Story 1 - 向配置好的 webhook 主动推送内容（P1）

**Goal**: 向目标配置中的 URL 发送一次包含原始内容的 POST。

**Independent Test**: 本地假 webhook 收到一次 POST；method/path/body 分别对号，URL 不硬编码。

- [X] T006 [US1] 先在 oryxos-tool/src/test/java/com/oryxos/tool/notify/WebhookNotifyAdapterTest.java 写 `send_postsContentToConfiguredUrl`（@DisplayName 保留“发送内容到配置的 webhook”）,断言 POST、目标 path 与 body content；运行测试确认因实现缺失而红
- [X] T007 [US1] 在 oryxos-tool/src/main/java/com/oryxos/tool/notify/WebhookNotifyAdapter.java 实现 `NotifyChannelAdapter`:注入 `RestClient.Builder`、POST `application/json`、body `Map.of("content", content)`、`retrieve().toBodilessEntity()`；跑 T006 变绿

## Phase 4: User Story 2 - 通知失败必须可见（P1）

**Goal**: 配置错误和 webhook 服务错误都 fail-loud，不产生伪成功。

**Independent Test**: 缺 URL 时请求数保持 0；4xx、5xx 与连接失败均抛 RestClient 异常。

- [X] T008 [US2] 先扩展 oryxos-tool/src/test/java/com/oryxos/tool/notify/WebhookNotifyAdapterTest.java:新增 `missingUrl_failsBeforeRequest`、`clientError_propagates`、`serverError_propagates`、`networkError_propagates`,分别断言网络前拒绝及 4xx/5xx/连接错误上抛
- [X] T009 [US2] 完善 oryxos-tool/src/main/java/com/oryxos/tool/notify/WebhookNotifyAdapter.java 的 target/url 参数校验,不 catch RestClient 运行时异常；跑 T008 变绿

## Phase 5: User Story 3 - 中立契约可扩展（P2）

**Goal**: 第二个目标复用同一契约，调用方无需平台专属参数。

**Independent Test**: 同一 Adapter/Target 契约连续向两个不同目标发送，请求分别到达各自 path。

- [X] T010 [US3] 扩展 oryxos-tool/src/test/java/com/oryxos/tool/notify/WebhookNotifyAdapterTest.java:新增 test-local 第二种 `NotifyChannelAdapter` 实现及 `differentAdapters_useSameContract`,验证两种 Adapter 均使用相同 `send(NotifyTarget,String)` 契约且无新增生产 public 类型

## Phase 6: Polish & 节级门禁

- [X] T011 运行 `mvn -pl oryxos-tool -am test` 验证 oryxos-tool/src/test/java/com/oryxos/tool/notify/WebhookNotifyAdapterTest.java 与前序模块测试全绿
- [X] T012 运行 `mvn -pl oryxos-tool -am verify "-Ddependency-check.skip=true"`,确认 oryxos-tool/src/main/java/com/oryxos/tool/notify/ 全部通过 Spotless/Checkstyle/P3C/SpotBugs/FindSecBugs
- [ ] T013 基于根 pom.xml 运行 `mvn clean verify` 完整门禁,确认全仓库回归与 OWASP Dependency-Check 全绿
- [X] T014 对 oryxos-tool/src/main/java/com/oryxos/tool/notify/ 执行 H4 自查:无明文 webhook/key、无 Reactor/CompletableFuture/自建线程池、Adapter 未注册为 Agent Tool且保留 24 节 Sandbox 接线边界
- [X] T015 按 specs/004-notify/quickstart.md 复跑命令并核对 specs/004-notify/contracts/notify-contract.md；确认课件交付物存在性与下方延期账本完整

## Deferred Integration Ledger（非本 feature 可执行任务）

以下交付物属于课件总设计，但依赖尚未就绪，禁止在 019 创建半成品：

- **DR-001 / 第 20、24 节**：复用 `OryxTool`/`ToolResult`,补 `@Tool` Schema/ToolRegistry 适配、
  Profile 目标解析与 `Sandbox.enforce(HTTP_REQUEST, url)`,落地
  `NotifyToolsTest` 的未配置报错、channel 缺省取第一条、enforce-before-send `InOrder` 三项。
- **DR-002 / 第 27、28 节**：通过统一 `ToolExecutor` 验证 notify 成败写 `tool_invocations`。

## Dependencies & Execution Order

- Setup: T001 → T002。
- Foundational: T003 与 T004 可并行；T005 核对前序配置契约；T002~T005 完成后才能开始 T006。
- Stories: US1(T006→T007) → US2(T008→T009) → US3(T010)。US2 依赖可发送的 Adapter；US3 复用其结果。
- Polish: T011 → T012 → T013 → T014/T015。

## Parallel Opportunities

- T003/T004 修改不同文件，可并行。
- T014 的静态不变量扫描与 T015 的文档/存在性核对可在完整门禁后并行。

## Implementation Strategy

1. 先锁依赖，避免实现中途发现版本未管理。
2. 契约/目标先行，保持渠道中立。
3. 每个 Story 都先写对应 harness，再写最小实现使其变绿。
4. MVP 需要 US1 + US2：能发送但会吞错不构成可交付能力；US3 随后钉死可扩展性。
5. 019 只宣布“Notify 第一批 Adapter 完成”；NotifyTools 完整能力必须等延期账本清零。

## Format Validation

- 所有可执行任务均为 `- [ ] Txxx [P?] [US?] 描述 + 精确文件路径`。
- User Story 阶段任务均带 `[US1]`/`[US2]`/`[US3]`。
- 延期账本不使用任务 checkbox，避免 `/speckit-implement` 误执行后续课程工作。
