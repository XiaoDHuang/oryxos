# Tasks: 统一 Tool 体系（第 20 节）

**Branch**: `020-lesson20-tool-system` | **Created**: 2026-08-26

**Input**: [spec.md](spec.md)、[plan.md](plan.md)、[research.md](research.md)、[data-model.md](data-model.md)、
[Tool API](contracts/tool-api.md)、[内置与安全契约](contracts/builtin-and-sandbox.md)、
[MCP 契约](contracts/mcp-stdio.md)、[quickstart.md](quickstart.md)；宪法 v2.0.0。

**Status**: 66项任务已全部完成；开发与安全修复由主模型执行，最终回归/完整门禁由Spark执行并已通过。

**Tests**: 本核心特性测试为必需。先写测试、确认缺失行为导致失败，再实现并跑绿对应模块；
接口尚未创建时先保留契约测试，接口成形后核对实际失败断言，不以编译失败代替行为验证。
测试方法英文名、中文 @DisplayName；不删断言、不 @Disabled、不放宽阈值。单测默认执行，
本地进程/SQLite 冒烟标 @Tag("integration")，显式执行并核对非零测试数量。

## Format / 路径与实施边界

- 每项采用 `- [ ] Tnnn [P?] [USn?] 描述 + 文件路径`；路径均相对仓库根目录。
- [P] 仅表示同一阶段、共同依赖完成后可在不同文件上并行，不授权自动启动子代理，也不并发运行
  修改同一 POM/target 的 Maven 构建。未标 [P] 的任务顺序执行。
- T001–T013 为共同前置；US1/US2 为 P1，US3/US4 为 P2；T051–T060 为跨故事收口。
- 本节只交付七个内置工具；Memory 两个、真实白名单和五个扩展分别登记 DR-001/002/003。
- 保留 String JSON 的 OryxTool、旧 ToolResult 构造/工厂、静态 ProfileContext、tool list 轻命令。
- ActionType 仅 FILE_ACCESS / SHELL_EXEC / HTTP_REQUEST；PermissiveSandbox 只在 test，生产默认拒绝。
- 不改宪法、不新增模块/表/端点/Profile 字段/生产绕过开关，不自动 commit/push/package.sh。
- 原由另一任务同步的 POM、CLI 测试、JpaSessionManager 和 OWASP suppression 修改必须保留；用户随后明确将最终安全修复转入本会话。
- 按用户最新要求：主模型负责实现、修复及开发阶段红/绿单测与局部集成；T055/T056/T058最终回归
  与快速门禁交给 `gpt-5.3-codex-spark`。Spark只运行指定命令，返回同一代码快照标识/退出码/
  用例数/报告路径/失败摘要，不改源码、POM、抑制规则或验收阈值；失败由主模型修复后再复跑。
  Spark不可用时暂缓最终回归并告知用户，不静默换模型；OWASP仅在用户确认同步完成后由本会话启动。
  早期Spark测试证据保留原执行者记录；此分工不修改OryxOS Profile/provider模型。
- 实施证据统一写入 `specs/005-tool-system/verification.md`（实施时创建，本轮不生成报告空壳）。
  记录实际命令、时间、退出码、测试数量和工作树/提交标识；没有运行的项明确写未运行。

## Phase 1: Setup（T001–T004）

**Purpose**: 确认一致性、环境和已批准依赖；不重建九模块骨架，不升级 Specify CLI。

- [x] T001 使用 speckit-analyze 对 `specs/005-tool-system/spec.md`、`specs/005-tool-system/plan.md`、`specs/005-tool-system/tasks.md` 做实施前只读一致性审查；严重未决项先处理并在本文件追加可追踪修复任务，不带冲突开工。
- [x] T002 核对分支和既有未提交改动，复查前序核心类；验证 JDK21 与 `mvn -version` 可返回，若仍无输出先做有界诊断，不停其他任务进程；在 `specs/005-tool-system/research.md` 更新环境证据，不更改任何安全门禁。
- [x] T003 在 `pom.xml` 锁定 MCP SDK0.18.3、networknt2.0.0 并将现有 integration 排除值接到 `test.excludedGroups` 构建属性；在 `oryxos-tool/pom.xml` 声明计划内依赖和直接使用的 Spring AI model，保留另一任务版本修改与已有 MockWebServer，不引入 MCP 自动执行 starter/WebFlux transport。
- [x] T004 运行 quickstart 的 dependency:tree，核实最终依赖解析和注解、Schema、同步 MCP、HTTP 时限/重定向、JDK进程 API；将可复核签名与实际版本记入 `specs/005-tool-system/research.md`，不把 artifact POM 当最终解析结果；API不符按软门禁暂停，不临时升级。

**Checkpoint**: 环境和依赖证据可用，实施前分析无未处理阻断项。此阶段不运行 OWASP。

## Phase 2: Foundational（T005–T013）

**Purpose**: 四个故事共用的结果、安全前向接口、参数验证和注解适配基础；必须先完成。
AnnotatedToolAdapter 在这里完成共享执行基础，US4 再完成业务 Bean/代理发现和自动装配，不反向阻塞 US2。

- [x] T005 [P] 在 `oryxos-core/src/test/java/com/oryxos/core/tool/ToolResultTest.java` 写四参构造/旧工厂兼容、五参结果、成功不可重试和失败语义测试，并验证 OryxTool 的 `String getInputSchema()`、`execute(String argumentsJson)` 等四方法签名不变。
- [x] T006 [P] 在 `oryxos-tool/src/test/java/com/oryxos/tool/sandbox/SandboxContractTest.java` 写三值动作、必填 target、拒绝异常和测试显式放行契约测试，不实现真实白名单策略。
- [x] T007 [P] 在 `oryxos-tool/src/test/java/com/oryxos/tool/ToolArgumentValidatorTest.java` 写对象输入、重复JSON键、必填/类型/未知字段、本地引用、未知方言和外部引用拒绝测试；证明注册/校验不发外网请求、错误不泄漏敏感实例值。
- [x] T008 扩展 `oryxos-core/src/main/java/com/oryxos/core/tool/ToolResult.java` 的 retryable 并保留四参构造/ok/fail，更新 `oryxos-core/src/main/java/com/oryxos/core/tool/OryxTool.java` 的执行/异常说明但不改四方法签名；跑绿 T005。（依赖 T005）
- [x] T009 在 `oryxos-tool/src/main/java/com/oryxos/tool/sandbox/Sandbox.java`、`oryxos-tool/src/main/java/com/oryxos/tool/sandbox/SandboxAction.java`、`oryxos-tool/src/main/java/com/oryxos/tool/sandbox/ActionType.java`、`oryxos-tool/src/main/java/com/oryxos/tool/sandbox/SandboxViolationException.java` 实现前向契约；仅在 `oryxos-tool/src/test/java/com/oryxos/tool/sandbox/PermissiveSandbox.java` 放临时放行实现，跑绿 T006。（依赖 T006）
- [x] T010 在 `oryxos-tool/src/main/java/com/oryxos/tool/ToolArgumentValidator.java` 实现包私有 Jackson/networknt 校验辅助；拒绝外部 $ref/$dynamicRef/$recursiveRef 和未知方言，保留本地引用；跑绿 T007，不新增 public 校验框架。（依赖 T007）
- [x] T011 在 `oryxos-tool/src/test/java/com/oryxos/tool/AnnotatedToolAdapterTest.java` 先写共享适配测试：Schema生成、参数绑定、String/对象/null/ToolResult返回、错误/重试语义、安全异常透传、默认插件guard拒绝；证明非法参数和拒绝路径不会调用方法体。（依赖 T008、T009、T010）
- [x] T012 在 `oryxos-tool/src/main/java/com/oryxos/tool/AnnotatedToolAdapter.java` 实现共享适配：Spring AI只生成Schema，OryxOS自行反射调用同步方法，保留ToolResult语义；以包内接点区分内置安全链和默认拒绝的Java插件，不调用ToolCallback执行，不新建Policy/配置键；跑绿 T011。（依赖 T011）
- [x] T013 执行 `mvn -pl oryxos-tool -am test` 检查基础契约与已有core/tool测试；将实际数量、失败修复及命令记入 `specs/005-tool-system/verification.md`，失败不进入用户故事。（依赖 T008、T009、T010、T012）

**Checkpoint**: 兼容接口、安全前向值对象、参数校验和共享注解执行均可独立验证，真实生产白名单仍未交付。

## Phase 3: US1 — 所有来源的工具使用统一契约（P1，MVP）

**Goal**: 唯一注册表、Profile精确授权、兼容Provider、正确的有限重试与最终审计。

**Independent Test**: 以受控 OryxTool/注解 fixture 验证注册/过滤/执行器，不依赖真实IO或MCP服务。
该MVP证明来源无关的契约；真实MCP适配接入后仍须在T053重跑全来源参数化测试，不能用替身冒称MCP已连通。

### Tests first

- [x] T014 [P] [US1] 在 `oryxos-tool/src/test/java/com/oryxos/tool/ToolRegistryTest.java` 写非空元数据/有效Schema、重名拒绝、registerAll全批原子性、只读快照/冻结和Profile精确子集测试；空列表为空，未知/重复声明明确报错，不能静默少给或多给工具。
- [x] T015 [P] [US1] 在 `oryxos-tool/src/test/java/com/oryxos/tool/OryxToolContractTest.java` 建立从Registry读取的 allRegisteredTools 参数源，保留课件三项 assertNotNull 并加非空白/唯一名称断言；测试数据不为空、不手写一个永远不扩展的工具清单。
- [x] T016 [P] [US1] 扩展 `oryxos-core/src/test/java/com/oryxos/core/react/ToolExecutorTest.java` 覆盖返回失败不记成功、首次+最多3次重试、100/200/400ms、最终一次审计/总耗时、取消、审计故障不重放、缺Profile/未授权/未知/非法结果；在 `oryxos-core/src/test/java/com/oryxos/core/react/ReActLoopTest.java` 固化外层默认10轮及Profile覆盖与内部重试独立，不削弱已有断言。
- [x] T017 [P] [US1] 扩展 `oryxos-core/src/test/java/com/oryxos/core/react/PromptBuilderTest.java` 覆盖精确工具集合、空集合、重复/未知声明显式失败及不影响其他合法Profile；保留原有Bootstrap/Skill/历史行为断言。

### Implementation

- [x] T018 [P] [US1] 在 `oryxos-tool/src/main/java/com/oryxos/tool/ToolRegistry.java` 实现契约中的注册/注解注册、contains/all/forProfile/asMap和包内冻结，使用共享Adapter/validator，批量先校验后提交；跑绿 T014、T015，不做热更新。（依赖 T014、T015）
- [x] T019 [P] [US1] 修改 `oryxos-core/src/main/java/com/oryxos/core/react/ToolExecutor.java`：检查当前Profile与真实调用授权，只重试明确可重放的瞬态结果、保留中断，一次最终审计取真实状态且审计失败不触发工具重放；保留公开构造/execute/审计端口，时间/退避测试接点保持包私有；跑绿 T016。（依赖 T016）
- [x] T020 [P] [US1] 修改 `oryxos-core/src/main/java/com/oryxos/core/react/PromptBuilder.java` 的工具解析，未知/重复声明明确失败且不改历史/记忆接线逻辑；保持Map构造与core依赖方向，跑绿 T017。（依赖 T017）
- [x] T021 [US1] 执行 `mvn -pl oryxos-provider,oryxos-tool -am test`，验证统一接口、Provider Schema适配/自动执行禁用、ReAct外层上限、执行器审计和Registry测试；将覆盖模块与实际数量写入 `specs/005-tool-system/verification.md`。（依赖 T018、T019、T020）
- [x] T022 [US1] 对本故事运行 speckit-analyze，核对 FR-001/002/003/011/013 与 `specs/005-tool-system/tasks.md`、实现和证据；发现补救项追加稳定ID并修复后复验，记录可提交检查点但不自动提交。（依赖 T021）

**Checkpoint**: US1 可作为内部开发MVP；这不是可开放外部IO的生产发布点。

## Phase 4: US2 — Agent 使用核心内置工具操作外部世界（P1）

**Goal**: 七个内置工具的正常路径、失败路径和安全先行，接回第19节通知能力。

**Independent Test**: 临时目录、假进程/受控本地子进程、MockWebServer和Adapter替身；拒绝时读写、
启动、发送计数为零。测试放行与生产默认拒绝分开验证。

### Tests first

- [x] T023 [P] [US2] 在 `oryxos-tool/src/test/java/com/oryxos/tool/builtin/FileToolsTest.java` 写UTF-8读/写/列目录、空内容覆盖、稳定排序、路径/类型/编码/权限失败和enforce先行测试；路径仅用临时目录，拒绝后零文件副作用。
- [x] T024 [P] [US2] 在 `oryxos-tool/src/test/java/com/oryxos/tool/builtin/ShellToolsTest.java` 写bash原始命令参数、30秒生产上限、合并输出/非零退出、1MiB上限、超时/中断与子进程/管道回收、拒绝零启动、无bash明确失败测试；默认测试用受控替身，真实短超时回收用integration标记，不改生产阈值。
- [x] T025 [P] [US2] 在 `oryxos-tool/src/test/java/com/oryxos/tool/builtin/HttpToolsTest.java` 写GET/POST成功、合法JSON body、URL校验、5秒连接/30秒读取、1MiB流式上限、3xx不跟随、4xx不重试/GET5xx可重试/POST不重放；保留课件正常响应与越界assertThrows，并检查拒绝零请求。
- [x] T026 [P] [US2] 在 `oryxos-tool/src/test/java/com/oryxos/tool/builtin/NotifyToolsTest.java` 写缺Profile/配置、默认首目标、type唯一匹配/歧义、非法URL/残留占位符、空白content、先enforce后send、一次发送和配置秘密不进结果测试；复用第19节契约，不新增目标标识字段。
- [x] T027 [P] [US2] 在 `oryxos-tool/src/test/java/com/oryxos/tool/notify/WebhookNotifyAdapterTest.java` 保留第19节断言，增加时限、禁止自动重定向及非法目标的回归；确认Builder/send签名、POST和content payload不变，不引入平台专用协议。

### Implementation

- [x] T028 [P] [US2] 在 `oryxos-tool/src/main/java/com/oryxos/tool/builtin/FileTools.java` 实现read_file/write_file/list_dir，输入字段遵循契约，Path规范化后先enforce再Files操作，写入不隐式建父目录；错误明确且写入不自动重试，跑绿 T023。（依赖 T023）
- [x] T029 [P] [US2] 在 `oryxos-tool/src/main/java/com/oryxos/tool/builtin/ShellTools.java` 实现bash -c同步执行、虚拟线程排空合并管道、30秒/1MiB边界、超时/中断销毁与关闭；不自建固定线程池、不并行工具、不切换Shell语言，失败默认不可重试，跑绿 T024。（依赖 T024）
- [x] T030 [P] [US2] 在 `oryxos-tool/src/main/java/com/oryxos/tool/builtin/HttpTools.java` 实现http_get/http_post同步RestClient，先enforce、禁重定向、时限与限量读取；只给可安全重放GET瞬态故障标重试，诊断脱敏，跑绿 T025。（依赖 T025）
- [x] T031 [P] [US2] 在 `oryxos-tool/src/main/java/com/oryxos/tool/builtin/NotifyTools.java` 从静态ProfileContext解析唯一NotifyTarget，再enforce再调用已有Adapter；失败返回不可重试结果并交ToolExecutor审计，不缓存目标、不广播，跑绿 T026。（依赖 T026）
- [x] T032 [P] [US2] 收紧 `oryxos-tool/src/main/java/com/oryxos/tool/notify/WebhookNotifyAdapter.java` 的客户端时限、重定向与目标校验，保留第19节公开签名和payload，不加新依赖/全局Provider客户端定制；跑绿 T027。（依赖 T027）
- [x] T033 [US2] 将七个实际内置工具经注解适配纳入 `oryxos-tool/src/test/java/com/oryxos/tool/OryxToolContractTest.java` 与 `oryxos-tool/src/test/java/com/oryxos/tool/ToolRegistryTest.java`，用独立Profile证明名字集合恰好一致，Memory/五个扩展未伪造注册；通过Executor验证安全拒绝被审计为失败。（依赖 T028、T029、T030、T031、T032）
- [x] T034 [US2] 跑core/tool测试与显式Shell本地回收冒烟，运行本故事speckit-analyze，将七工具、通知顺序和失败重试分类证据写入 `specs/005-tool-system/verification.md`；真实白名单仍标DR-002，不以测试替身宣称生产可用。（依赖 T033）

**Checkpoint**: 七工具的受控功能/安全顺序已验证，第19节NotifyTools测试债完成本节接线部分。

## Phase 5: US3 — 业务方通过 MCP 接入外部工具（P2）

**Goal**: 配置、同步stdio、列表/调用适配、故障隔离与生命周期。依赖US1，不依赖真实MCP/LLM。

**Independent Test**: 默认用SDK替身证明映射和坏服务隔离；先显式跑测试自带Java stdio进程，
证明真实传输/编码/env/退出。连通性门禁通过前不得展开McpClientService/McpToolAdapter业务实现。

### Connectivity gate before business implementation

- [x] T035 [US3] 在 `oryxos-tool/src/test/java/com/oryxos/tool/mcp/SchemaPreservingMcpJsonMapperTest.java` 先写原始JSON含allOf/$defs/对象additionalProperties的保真、离线约束校验与绑定清理测试；在 `oryxos-tool/src/test/java/com/oryxos/tool/mcp/McpStdioIntegrationTest.java`、`oryxos-tool/src/test/java/com/oryxos/tool/mcp/McpStdioFixture.java` 写Java stdio探针，覆盖initialize/单页list/call/close、UTF-8、env隔离和退出；进程用例标integration，不改全局env或依赖企业服务。
- [x] T036 [US3] 在 `oryxos-tool/src/main/java/com/oryxos/tool/mcp/ConfiguredStdioTransport.java` 实现已核实的env隔离接点；在 `oryxos-tool/src/main/java/com/oryxos/tool/mcp/SchemaPreservingMcpJsonMapper.java` 实现包私有mapper装饰器，于ListToolsResult转换前验证/保留原始Schema，DTO只用兼容副本，按页对象身份绑定且消费/失败/取消/冻结即清理，最多32个待消费页；不改SDK源码、内部Map或新增依赖。（依赖 T035）
- [x] T037 [US3] 按quickstart先运行SchemaPreservingMcpJsonMapperTest，再显式运行McpStdioIntegrationTest；核对非零用例、真实mapper原始Schema往返、进程回收和env隔离，将SDK证据记入 `specs/005-tool-system/verification.md`；未过不展开MCP业务，不能只构造DTO或用mock结果冒充连通。（依赖 T036）

### Tests first

- [x] T038 [P] [US3] 在 `oryxos-tool/src/test/java/com/oryxos/tool/mcp/McpClientServiceTest.java` 写配置/env/重名隔离、初始化/分页失败、原子注册、安全拒绝零启动和关闭；补循环游标、32/33页、1000/1001工具、30秒总预算、取消/迟到响应零注册及继续好服务测试；保留不抛错、good_mcp_tool存在、bad_mcp_tool不存在与WARN守点。（依赖 T037）
- [x] T039 [P] [US3] 在 `oryxos-tool/src/test/java/com/oryxos/tool/mcp/McpToolAdapterTest.java` 从T035真实mapper保存的原始Schema证明getInputSchema深相等及allOf等约束实际拦截非法参数，禁止DTO回退；另覆盖参数保真、isError/各种content、Profile授权、安全先行、超时不盲重试与秘密不回显。（依赖 T037）

### Implementation

- [x] T040 [US3] 在 `oryxos-tool/src/main/java/com/oryxos/tool/mcp/McpServerConfig.java` 实现包私有不可变配置载体及name/stdio/非空argv/env约束，不增args配置键、不转成shell命令、不把配置类型暴露给core。（依赖 T038）
- [x] T041 [US3] 在 `oryxos-tool/src/main/java/com/oryxos/tool/mcp/McpToolAdapter.java` 保存完整原始Schema，用同一份数据发布参数说明并经注入的JDK校验回调验证参数，再授权/enforce/同步转发；不从SDK JsonSchema重建或丢约束，处理各种结果且不另写重试/审计，跑绿 T039。（依赖 T039、T040）
- [x] T042 [US3] 在 `oryxos-tool/src/main/java/com/oryxos/tool/mcp/McpClientService.java` 实现配置/env及逐服务发现：单页listTools(cursor)、循环游标/32页/1000工具上限、包含初始化/清单校验的30秒总预算，初始化自身10秒/RPC30秒；辅助虚拟线程不写Registry，仅调用线程提交按时完成未取消的批次，清理另最多等10秒；消费原始Schema绑定、关闭SDK schema caching、失败继续好服务，跑绿 T038。（依赖 T040、T041）
- [x] T043 [US3] 扩展 `oryxos-tool/src/test/java/com/oryxos/tool/mcp/McpStdioIntegrationTest.java`，用实际Service/Adapter证明配置→原始Schema/约束→有界分页→调用/关闭闭环；fixture提供循环游标、超限和延迟响应，断言超时线程退出、迟到结果零注册且好服务仍可用；保持生产阈值，短预算仅包内测试注入，不启用生产Permissive。（依赖 T042）
- [x] T044 [US3] 跑默认MCP harness及显式stdio集成测试，运行本故事speckit-analyze，将映射、故障隔离、资源退出与env证据写入 `specs/005-tool-system/verification.md`；说明进程许可不代表隔离MCP内部任意IO。（依赖 T043）

**Checkpoint**: mock契约与本地真实stdio证据同时具备，不依赖企业服务；生产仍默认拒绝。

## Phase 6: US4 — Java 插件工具自动接入（P2）

**Goal**: 共享Adapter基础之上完成Spring Bean/代理自动发现，且不把实际执行交给Spring AI。

**Independent Test**: 本地Spring测试上下文加载普通/代理Java Bean，校验注册Schema和可控方法执行；
使用ToolExecutor检查成功/失败最终审计，测试放行与默认插件拒绝分别验证，无真实网络。

### Tests first

- [x] T045 [P] [US4] 扩展 `oryxos-tool/src/test/java/com/oryxos/tool/AnnotatedToolAdapterTest.java`，覆盖JDK/类代理可调用方法、DTO/泛型容器、非法签名、异步返回拒绝、名称缺省/描述必填、returnDirect/resultConverter不能启用旁路执行，保持共享基础断言不变。
- [x] T046 [P] [US4] 在 `oryxos-tool/src/test/java/com/oryxos/tool/JavaPluginRegistrationTest.java` 写自动发现、冲突、插件guard和唯一审计；同时先在 `oryxos-tool/src/test/java/com/oryxos/tool/ToolConfigurationTest.java` 写默认Sandbox拒绝零IO/零MCP启动、MCP完成后冻结、完整Schema校验回调接线、迟到结果不进入工具表、销毁close及Permissive不被生产扫描的失败用例；这些守点必须先于T049。

### Implementation

- [x] T047 [US4] 在 `oryxos-tool/src/main/java/com/oryxos/tool/AnnotatedToolAdapter.java` 补齐代理可调用方法、签名拒绝和返回转换边界，保留默认插件拒绝、参数验证及Sandbox异常语义，跑绿 T045；不新增public插件抽象。（依赖 T045）
- [x] T048 [US4] 在 `oryxos-tool/src/main/java/com/oryxos/tool/ToolRegistry.java` 完成registerAnnotated对Spring用户类/代理方法的正确发现及重复名校验，交由已有Adapter执行；不扫描ChatModel推断Provider，不把Skill/Bootstrap当工具。（依赖 T046、T047）
- [x] T049 [US4] 在 `oryxos-tool/src/main/java/com/oryxos/tool/ToolConfiguration.java` 实现包私有生产装配：默认拒绝、注解注册→有界MCP发现→冻结发布、销毁close；将同包ToolArgumentValidator通过JDK Consumer/BiConsumer接给MCP，不改helper可见性、不复制校验器；防止递归/提前快照/重复注册，跑绿T046两类测试，不将安全守点推迟到T051。（依赖 T034、T044、T046、T048）
- [x] T050 [US4] 跑Java插件和core/tool回归，运行本故事speckit-analyze，在 `specs/005-tool-system/verification.md` 记录自动发现、默认拒绝和唯一执行/审计路径证据，不改ReActLoop接口或引入框架自动执行。（依赖 T049）

**Checkpoint**: Java插件自动接入已验证；其默认生产IO权限仍未开放，不以注解声明替代安全审查。

## Phase 7: Polish & Cross-Cutting（T051–T060）

**Purpose**: 跨模块真实装配、SQLite端到端、前序回归、完整安全门禁与延期交接。

- [x] T051 仅在T046已建立的 `oryxos-tool/src/test/java/com/oryxos/tool/ToolConfigurationTest.java` 追加core消费者精确toolTable注入、同一集合、无tool装配时空表及坏Profile不拖垮其他Profile的回归，为T052先建立失败用例；默认拒绝/MCP顺序/冻结/销毁测试已在T049前完成，不在本任务首次补写。（依赖 T050）
- [x] T052 在 `oryxos-core/src/main/java/com/oryxos/core/config/CoreEngineConfiguration.java` 为已有ObjectProvider<Map<String,OryxTool>>注入位补toolTable限定并更新说明，保留公开方法签名和无tool模块的空表能力，不反向依赖ToolRegistry；跑绿 T051。（依赖 T051）
- [x] T053 在 `oryxos-tool/src/test/java/com/oryxos/tool/OryxToolContractTest.java` 将实际内置、Java Adapter和mock客户端支撑的实际MCP Adapter全部纳入Registry参数源，核对非空/唯一/精确子集；在 `oryxos-cli/src/test/java/com/oryxos/cli/ToolListCommandTest.java` 独立验证tool list只读声明、零Spring启动/零MCP连接，不改另一任务的OryxOsCliHelpTest。（依赖 T052）
- [x] T054 在 `oryxos-boot/src/test/java/com/oryxos/boot/ToolSystemIntegrationTest.java` 写并跑假LlmGateway→AgentService/ReAct→真实注册/执行器→实际FileTools操作临时文件→临时SQLite→最终回复的integration用例；重试场景另注册明确返回瞬态失败的测试工具，断言成功、失败、重试后结束各一条真实tool_invocations及正确状态/耗时，另验默认拒绝零副作用，不只mock审计。（依赖 T052、T053）
- [x] T055 由Spark执行最终回归：`mvn test` 和quickstart中的stdio/Boot/Shell显式integration命令，检查每个指定suite实际执行非零用例，保存前序全部模块和第20节结果至 `specs/005-tool-system/verification.md`；任何失败修实现，不把core/tool两模块结果写成全仓回归。（依赖 T054）
- [x] T056 由Spark运行最终快速门禁 `mvn clean verify "-Ddependency-check.skip=true"`，在 `specs/005-tool-system/verification.md` 记录Spotless/P3C/Checkstyle/SpotBugs/FindSecBugs/PMD及默认测试结果；只称快速门禁，不更改OWASP配置、不把它标成完整verify。（依赖 T055）
- [x] T057 对 `oryxos-core/src/main`、`oryxos-tool/src/main`、`oryxos-provider/src/main`、`oryxos-storage/src/main` 做H4六项与九模块/公开概念/中文注释检查，核对课件七类harness非空及本节构建JAR不含PermissiveSandbox；逐项证据写入 `specs/005-tool-system/verification.md`，保留真实待办而非用抑制/删断言过关。（依赖 T056）
- [x] T058 用户确认security/OWASP同步完成并将修复转入本会话后，主模型升级Tomcat 10.1.59与Swagger UI 5.32.14；Spark对147文件冻结快照执行未跳过插件的 `mvn clean verify`，195项测试、10模块静态门禁与Dependency-Check全部通过，前后快照一致；证据回填 `specs/005-tool-system/verification.md`。（依赖 T057）
- [x] T059 对 `specs/005-tool-system/spec.md`、`specs/005-tool-system/plan.md`、`specs/005-tool-system/tasks.md` 与当前实现执行最终一致性复核，运行speckit-analyze并检查代码符合契约；发现实现缺口追加稳定任务ID、补测修复后重验，不仅靠勾选判完成。可在T058待外部时先完成，结论须注明未全量封板。（依赖 T055、T056、T057）
- [x] T060 更新 `specs/005-tool-system/quickstart.md` 和 `specs/005-tool-system/verification.md`，交付六项DoD证据、课件交付物清单、前序触碰说明、风险review位置与可复制验证命令；核对DR-001/002/003归属及人工真服务项，按git实际差异汇报；无T058不得宣称本节完成，不自动提交/归档。（依赖 T059）

## Dependencies & Execution Order

```text
Setup T001–T004
  → Foundation T005–T013
    → US1 T014–T022（内部契约MVP）
      ├→ US2 T023–T034（内置工具）─────────┐
      └→ US3 T035–T044（先stdio探针，再业务）┤
                                           → US4 T045–T050（插件与统一装配）
                                             → T051–T057 本地验收
                                             → T059–T060 复核/如实交接
当前代码完整verify T058 ───────────────────────→ 全部满足后封板
```

- 默认按编号顺序；US2/US3生产实现文件互不依赖，可在共同前置完成且用户允许的协作方式下拆开。
- US3的T035→T036→T037为硬前置，T038–T044不能在其未过时展开。SDK签名核实不等于传输已通。
- US4共享Adapter已在Foundation就绪；US4的完整ToolConfiguration依赖US2和US3，不创建空MCP
  服务/假工具来提前完成装配。
- ToolRegistry、AnnotatedToolAdapter和OryxToolContractTest跨阶段都会扩展，后续阶段必须串行
  接续前面的修改，不能给两个并行执行者同时改同一个文件。
- 每个故事先跑独立验收再做只读一致性检查；提交稳定点由用户决定，不自动执行Git写操作。

## Parallel Examples（仅逻辑机会，不自动派发）

| 故事 | 可并行的独立文件任务 | 先决条件 / 禁止并行项 |
|---|---|---|
| US1 | T014/T015/T016/T017测试；T018/T019/T020实现 | Foundation及各自测试就绪；集成构建/证据写入串行 |
| US2 | T023–T027测试；T028–T032实现 | US1就绪、对应测试先写；T033共享契约用例随后串行 |
| US3 | T038/T039测试 | T037真实stdio门禁已过；配置/适配/服务/集成按依赖顺序 |
| US4 | T045/T046测试 | 共享Adapter已就绪；T047→T048→T049顺序合并 |

## Harness-first 对账

此表是生成时的任务映射，不是实现存在性或通过证明。源文件实现任务均已有前置/伴随测试。

| 交付物 / 改造 | 测试任务 | 实现任务 |
|---|---|---|
| OryxTool/String兼容 + ToolResult | T005 | T008 |
| Sandbox/SandboxAction/ActionType/SandboxViolationException + test PermissiveSandbox | T006 | T009 |
| 包私有ToolArgumentValidator | T007 | T010 |
| AnnotatedToolAdapter共享基础 | T011 | T012 |
| ToolRegistry + OryxToolContractTest | T014、T015 | T018 |
| ToolExecutor重试/授权/审计 | T016 | T019 |
| PromptBuilder精确声明 | T017 | T020 |
| FileTools/read_file/write_file/list_dir | T023 | T028 |
| ShellTools/shell | T024 | T029 |
| HttpTools/http_get/http_post | T025 | T030 |
| NotifyTools/notify | T026 | T031 |
| 既有WebhookNotifyAdapter传输收紧 | T027 | T032 |
| 包私有ConfiguredStdioTransport | T035 | T036 |
| 包私有SchemaPreservingMcpJsonMapper / 原始Schema | T035 | T036 |
| MCP配置name/transport/command/env、McpServerConfig、McpClientService | T038 | T040、T042 |
| McpToolAdapter | T039 | T041 |
| Java代理/自动发现、默认安全与MCP完成后冻结 | T045、T046 | T047、T048、T049 |
| CoreEngineConfiguration精确Map注入 | T051 | T052 |

**课件核心对账**：代码全部映射；课件七个必需测试类全部有任务；`.oryxos/mcp_servers.yaml` 与
Profile.tools消费/过滤均覆盖；本节无新增表，复用tool_invocations端到端检查在T054。
额外项仅为plan已列的包私有基础设施、必要回归/本地探针/端到端验收，不新增产品公开概念。

## Requirements → Tasks

| 需求 | 覆盖任务 |
|---|---|
| FR-001 / FR-002 | T005、T008、T014、T015、T018、T053 |
| FR-003 | T014、T016、T017、T018、T019、T020、T051 |
| FR-004 | T011、T012、T021、T045–T050、T057 |
| FR-005 | T023、T028、T033 |
| FR-006 | T024、T029、T034 |
| FR-007 | T025、T030 |
| FR-008 | T026、T027、T031、T032 |
| FR-009 / FR-010 | T035–T044 |
| FR-011 | T016、T019、T033、T046、T054 |
| FR-012 | T001、T057、T059 |
| FR-013 | T016、T019、T021、T025、T030、T039、T041、T054 |
| FR-014 | T006、T009、T011、T012、T038、T046、T049、T051、T057 |
| FR-015 | T053、T055 |
| SC-007（默认无真实外部依赖） | 各harness、T035/T054显式本地integration、T055 |
| SC-008（延期有归属） | 下列台账、T060 |

## Deferred / External Gates（不混入本节实现任务）

| ID | 后续归属 | 必须补齐的内容 / 解除条件 |
|---|---|---|
| DR-001 | 第22节 | save_memory/recall_memory注册，默认Profile不再引用未知Memory，记忆Demo回归 |
| DR-002 | 第24节 | 三值ActionType真实白名单、课件四值偏差同步、MCP/Java安全边界复核、生产外部动作放行验收 |
| DR-003 | 核心九Tool完成后 | 必须补edit_file/grep/glob/ask_user/web_search；先同步TechnicalSolution和AGENTS范围/节奏，再生成实施任务 |
| 完整门禁 | 本会话修复、Spark执行当前代码验证 | T058已由未跳过插件的完整verify满足；不把旧报告、快速verify或仅OWASP数据库同步视为全量通过 |
| 人工演示 | 安全接线完成后由用户安排 | 真模型+SKILL.md+批准的真MCP、Java插件、审计目检；tool list只证明声明 |

## Implementation Strategy / 固定停点

1. 推荐先完成Setup + Foundation + US1作为内部MVP，检查契约/授权/重试审计，不开放生产IO。
2. 再逐组完成US2，遵守安全先行与测试伴随；US3先跑真实本地stdio探针，之后才做服务/Adapter。
3. US4统一Spring装配，最后用全来源契约和SQLite端到端证明整条链路，不只依赖单测mock。
4. 本地可执行验证、最终审查与完整门禁分开报告；无完整证据不宣布“第20节完成”。
5. **用户已要求归档提交；T058完整证据取得后才执行提交。**

**任务统计**：66项；原始任务60项（共同前置13项、US1 9项、US2 12项、US3 10项、US4 6项、
跨故事收口10项）+一致性收口6项。当前66项均已有证据并完成。
其中原始任务24项标注[P]；课件24个代码/方法/测试/配置字面量均有映射，主要测试均先于对应实现。

## Phase 8: Convergence（实现一致性修复）

- [x] T061 修复 `oryxos-tool/src/main/java/com/oryxos/tool/ToolConfiguration.java` 的默认Sandbox/MCP Bean装配顺序：改为真正的Boot自动配置并增加AutoConfiguration.imports；在 `ToolConfigurationTest.java` 覆盖默认拒绝与用户Bean优先，不再依赖测试配置类先声明，确保第24节真实Sandbox可替换。per Constitution VI / DR-002（partial）
- [x] T062 先在 `oryxos-tool/src/test/java/com/oryxos/tool/builtin/ShellToolsTest.java` 增加进程已退出但InputStream.available暂时为0、随后出现尾部字节的回归，再修改 `ShellTools.java` 采用有界稳定排空逻辑，既不丢尾部输出也不恢复Windows阻塞关闭。per FR-006 / SC-003 / SC-009（partial）
- [x] T063 在 `oryxos-tool/src/test/java/com/oryxos/tool/mcp/McpClientServiceTest.java` 增加普通短环境变量不会误伤工具元数据、敏感键仍保护秘密的回归；在 `McpServerConfig.java` / `McpClientService.java` 明确无新增配置键的敏感环境变量识别规则并同步契约说明。per FR-010 / spec: sensitive environment edge case（partial）
- [x] T064 在 `oryxos-tool/src/test/java/com/oryxos/tool/mcp/McpToolAdapterTest.java` 增加已知配置秘密作为结构化结果字段名时失败关闭的用例；修改 `McpToolAdapter.java` 防止字段名绕过现有文本节点脱敏。per data-model §4 / T039（partial）
- [x] T065 在 `specs/005-tool-system/plan.md` Complexity Tracking、`tasks.md` 与 `verification.md` 记录用户批准的Shell原始 `bash -c` 单点静态分析例外；仅在 `ShellTools.shell` 添加带Sandbox先行和DR-002归属说明的 `COMMAND_INJECTION` 定点抑制，复核全局规则未变并由Spark重跑T056。per FR-006 / FR-014 / T056（partial）
- [x] T066 核对仓库无 `ToolExecutor` 子类后，在 `specs/005-tool-system/plan.md` 记录执行器非扩展点及因构造安全门禁设为final的兼容决定；保持公开构造、execute和审计端口签名不变，并同步spec/plan当前实施状态。per plan: compatibility / T019（unrequested）
