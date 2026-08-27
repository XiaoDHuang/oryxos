# Implementation Plan: 统一 Tool 体系

**Branch**: `020-lesson20-tool-system` | **Date**: 2026-08-26 | **Spec**: [spec.md](spec.md)

**Input**: `specs/005-tool-system/spec.md`；第 20 节课件；技术方案 §6.1–6.8；宪法 v2.0.0

**Stage**: 实现已落地，正在执行一致性修复与最终门禁；实际进度见tasks.md/verification.md。Specify CLI锁定0.14.2。
`.specify/feature.json` 指向 `specs/005-tool-system`；setup-plan 输出的 `005-tool-system` 是目录
标识，实际 Git 分支为上列 `020-lesson20-tool-system`，不改名、不重建 feature。

## Summary

保持既有 OryxTool 的 JSON 文本接口，在 oryxos-tool 汇总内置、Java 注解、MCP 三种来源，
由唯一 ToolExecutor 完成 Profile 授权、有限重试和最终审计。本节交付七个内置工具：
`read_file`、`write_file`、`list_dir`、`shell`、`http_get`、`http_post`、`notify`；第 22 节加入
两个 Memory Tool 后才构成核心九个，不用占位实现凑数。

沿用用户继续 plan 的三项决定：ActionType 使用技术方案三值契约；PermissiveSandbox 仅用于测试，
运行时默认拒绝外部动作；tool list 保持轻量声明视图。本节证明契约和受控测试链路，
**不宣称真实 Sandbox 已交付，也不宣称外部工具已可默认对外开放**。

## Technical Context

**Language/Version**: JDK 21；同步阻塞 + virtual thread；中文注释和错误/审计诊断。

**Primary Dependencies**: 当前工作区 Spring Boot 3.5.16、Spring AI 1.1.8、Spring Web 6.2.19；
Jackson/SnakeYAML 沿用父 POM。计划在 tool 显式引入 MCP SDK 0.18.3 与 networknt JSON Schema
Validator 2.0.0，证据见 [research.md](research.md)。不引入 MCP 自动执行 starter 或 WebFlux transport。

**Storage**: 复用 SQLite + Spring Data JPA 的 tool_invocations，不改表或审计端口；配置来自
`.oryxos/mcp_servers.yaml` 与已有 Profile，不依赖 hibernate.ddl-auto=update。

**Testing**: JUnit 5、Mockito、AssertJ、MockWebServer 4.12.0；默认单测无真实外网。
本地 stdio 假进程和 SQLite 端到端测试标记 @Tag("integration")，CI 默认跳过、手工显式执行。

**Target Platform**: Linux 核心部署，bash 执行 Shell Tool；Windows 开发运行单测和 Java stdio
fixture。无 bash 时明确失败，不静默改成 cmd/PowerShell 的不同命令语义。

**Project Type**: 既有 Maven 九模块单体的工具能力增量。

**Performance Goals**: 沿用技术方案性能目标，不新增性能发布门槛，不把外部 IO 计为内部转发。
Shell 单次 30 秒；HTTP 连接 5 秒/读取 30 秒；MCP 初始化 10 秒/请求 30 秒。
HTTP 响应和 Shell 捕获输出各上限 1 MiB。
MCP 每服务发现总预算 30 秒（包含初始化、分页和清单校验），最多 32 页、累计 1000 个工具；
失败清理另最多等待 10 秒，不能把清理时间用作继续发现的宽限。

**Constraints**: HTTP/MCP 时限、大小上限、100/200/400ms 退避是本计划选择的内部常量，
不是事实源已有数字，不新增 YAML/Profile 字段。Shell 30 秒与最多重试 3 次为已确认约束。
避开 P3C/ASM 不支持的 Java 18+ 语法形态，不以关闭检查换绿。

**Scale/Scope**: 七个本节内置工具 + 统一 Plugin 执行契约；不新增模块、REST 端点、CLI 命令、
表、Profile 字段或生产安全绕过开关。Skill/Bootstrap 仍归 core 的 ContextLoader。

## Constitution Check

*设计准入与实施验收分开；设计通过不等于代码或全量门禁通过。*

| 原则 | Phase 0 准入与 Phase 1 复核 |
|---|---|
| I 自有 ReAct | 保持循环、轮数和顺序调度；重试只放 ToolExecutor |
| II Spring AI 仅协议/Schema | 仅注解和 Schema 生成；自己的 Adapter 反射执行，继续禁用自动执行 |
| III 显式 Provider 映射 | 不改映射及初始化策略；测试用假 LlmGateway |
| IV Profile / 统一 Tool | 三种来源统一 OryxTool；不把 SKILL.md 当 Tool；声明与实际调用双重授权 |
| V 审计落库 | 每逻辑调用最终一行；修复失败结果被记成功；审计故障不导致工具重放 |
| VI 安全边界 | IO 前 enforce；MCP 启动和调用均检查；未安全接线的 Java 插件默认拒绝；Permissive 不进生产 |
| VII 同步/虚拟线程 | 应用只用 sync MCP/阻塞 HTTP，不新增 Reactor API、WebFlux、CompletableFuture 或固定线程池 |
| VIII 状态外置 | 注册表/连接可重建，Session/审计仍在 SQLite；无新业务状态或迁移 |
| 九模块 / 核心范围 | Tool 保持单模块，core 不反向依赖 tool；不做 Policy、并行、工作流或治理层 |

MCP SDK 内部依赖 Reactor，已有 spring-ai-model 也传递依赖它。这里不把应用同步 API 冒称为
“依赖树没有 Reactor”；不选响应式应用框架，实施前复核最终依赖树。

**Post-design result**: 无需要修改宪法的设计例外。第 24 节真实白名单与人工真服务演示仍延期。
规划阶段尚无全量 `mvn clean verify` 证据；后续用户确认security/OWASP同步完成并将修复转入本会话，
最终以未跳过插件的完整门禁作为封板依据，快速门禁不能替代它。

## 关键设计

### 1. 兼容接口、授权与重试审计

- 保留 String getInputSchema / execute(String argumentsJson)；不硬替换课件的示意 JSON 类型。
- ToolResult 加 retryable，保留四参构造器及旧 ok/fail 工厂，旧失败默认不可重试。
- ToolExecutor 构造器和 execute 签名不变。缺 Profile、未授权、未知工具均明确失败、零 IO。
- 一逻辑调用首次 + 至多 3 次重试，仅消费明确标记为可安全重放的瞬态失败。http_get 对网络/
  读取故障及 5xx 可重试；Shell、写文件、POST、notify、无法确认重放安全的 MCP/Java 插件默认不重试。
  中断立即停止，不能把取消当瞬态网络错误。
- 最终审计在重试循环外写一次，取真实 success/content/errorMessage，总耗时含退避。
  审计异常不得进入工具重试范围；沿用 storage 的可观测、不阻断策略，不改审计端口/SQL。

### 2. 注册与启动顺序

ToolRegistry 校验非空元数据、可编译对象 Schema、全局唯一名称，提供注册、注解注册、contains、
all、Profile 精确过滤、只读 Map 快照。确切 API 见 [tool-api.md](contracts/tool-api.md)。

顺序固定：registry → 扫描合法 @Tool 方法 → 逐服务连接并原子注册 MCP 工具 → 发布 toolTable Bean
→ 构建 PromptBuilder/ToolExecutor。两个 core 构造器都有 Map.copyOf，不能启动后才补 MCP。
重启生效，不做热更新。CoreEngineConfiguration 仅为已有 Map 注入位补 @Qualifier("toolTable")，
不引用 ToolRegistry、不改公开方法签名。

PromptBuilder 不再静默略过未知名称；错误在使用对应 Profile 时报告，不让一个坏 Profile 在
全局装配阶段阻断其他 Profile。ToolExecutor 再校验实际工具名。MCP adapter 还验证当前 Profile
的 mcp_servers 引用；tools 仍是模型可见子集的唯一声明。

### 3. 注解执行与参数验证

扫描 Spring Bean 用户类及可在代理上调用的 public 同步实例方法。名称取 @Tool.name，空时取
方法名；描述必填；参数由 Jackson 绑定，保留已有 compiler parameters=true。
Schema 由 JsonSchemaGenerator 生成，networknt 本地验证后由自己的 Adapter 反射调用。
拒绝异步/响应式返回，禁止 MethodToolCallback.call / ToolCallingManager 实际执行。
返回 ToolResult 时保留原语义，普通值统一包装。SandboxViolationException 可透传给执行器，
保持课件 assertThrows 守点；ToolExecutor 统一转失败并审计，不使异常穿透 ReAct 生产边界。

### 4. 安全分阶段接线

公开前向契约为 Sandbox、SandboxAction、ActionType、SandboxViolationException，以及测试源码
中的 PermissiveSandbox。枚举固定 FILE_ACCESS / SHELL_EXEC / HTTP_REQUEST。
真实 Sandbox 缺位时装配拒绝 lambda，不新建 public DenyAllSandbox，不提供生产放行开关。

参数/目标解析可先做纯内存校验，任何外部 IO 前必须 enforce。未知 Java 插件的副作用不能从注解
推断，故生产反射调用 guard 默认拒绝；只有测试显式注入放行 guard，第 24 节再审查实际 IO 接线。
不伪造 FILE/HTTP target 来宣称任意 Java 代码已经隔离。
MCP 启动及 callTool 前均 enforce SHELL_EXEC，target 为配置的可执行文件；进程白名单不等于
其内部动作已隔离，本节生产默认拒绝不变。

### 5. 七个工具与第 19 节衔接

详见 [builtin-and-sandbox.md](contracts/builtin-and-sandbox.md)。文件使用 Path/Files + UTF-8。
Shell 用 bash，合并输出、虚拟线程排空管道避免 wait-before-read 死锁；超时/中断终止进程及可见
子进程并收尾，不引入工具并发。HTTP 用同步 RestClient，禁止自动重定向，限量读取而非整包后裁剪。

NotifyTools 放 builtin 包，用静态 ProfileContext.current()；channel 缺省取第一项，指定时按 type
精确匹配，零项/多项报错，不默默广播。只复用 webhook adapter，不新增目标 name 字段。
允许收紧 WebhookNotifyAdapter 的客户端时限/重定向设置，保留其构造/send 签名及 content payload。
第 19 节延期的缺配置、默认目标、enforce-before-send 测试本节用替身落地，真实白名单仍归 24。

### 6. MCP 接线

沿用 init 的 servers 根结构；command 为非空 argv 列表，不新增 args 键、不经 shell 拆串。
仅 stdio；缺文件为空集合，非法配置/缺失 env 占位符脱敏告警，单服务失败隔离。
完整契约见 [mcp-stdio.md](contracts/mcp-stdio.md)。

使用已核实 SDK sync builder/initialize/listTools/callTool/close；无参 listTools 已聚合分页。
审查发现其无参聚合没有循环游标/总量边界，因此实际调用必须用 `listTools(String cursor)` 单页 API。
首请求使用 SDK FIRST_PAGE；非空 nextCursor 已出现则拒绝；第 32 页仍有后续游标或累计超过
1000 个工具则整批失败。初始化自身仍限 10 秒，但整个发现操作共享 30 秒总截止时间。

每次只发现一台服务：由一个辅助虚拟线程执行初始化、逐页读取与清单校验，调用线程同步等待
剩余预算（JDK21 Thread.join(Duration)）；超时/中断则取消、清理并继续下一服务。不用 Reactor
应用 API、CompletableFuture 或固定线程池，不并行工具。工作线程不写 Registry，只有调用线程
确认结果按时完成且未取消后才能原子提交；迟到结果必须丢弃。清理最多等待 10 秒，失败必须可观测。
运行兼容性、超时取消/线程退出、子进程回收均由本地 fixture 证明，不能只凭签名宣称通过。

MCP 的原始 Schema 在 SDK DTO 转换前保存：包私有 `SchemaPreservingMcpJsonMapper` 装饰 SDK
McpJsonMapper，在 ListToolsResult 的 convertValue 入口捕获原始工具清单，为 SDK DTO 制作仅供
传输兼容的副本，并按返回页对象身份绑定完整 Schema。McpClientService 消费后立即移除绑定；
异常/取消/冻结时清空，待消费绑定至多 32 页，不能只按工具名跨响应覆盖。原始清单先经过完整
Schema/元数据检查；SDK 投影既不发布给 Provider，也不作为参数校验依据，缺原始绑定就拒绝注册。
显式保持 SDK enableCallToolSchemaCaching(false)，不让 SDK 的有限 JsonSchema DTO 代替完整契约。
仅受控发现期允许接收清单；冻结后自动刷新清单响应明确拒绝，不开启第二条无界发现/热注册路径。

ToolArgumentValidator 仍包私有；ToolConfiguration 在同包取得校验器，使用 JDK Consumer<String>
（Schema 检查）和 BiConsumer<String,String>（Schema/参数检查）传给 MCP 接线，避免子包直接访问
包私有类型或复制另一套校验器。McpToolAdapter 持有完整原始 Schema 的不可变文本，getInputSchema
和执行前参数校验使用同一份数据；禁止回退为序列化 SDK JsonSchema。

SDK 默认继承全部宿主环境。用包私有 StdioClientTransport 子类清空 ProcessBuilder 继承环境，
再由 SDK 注入已核实的最小系统变量集 + 该服务声明 env。不修改 SDK 内部 Map；未声明 Provider key
不得传入进程。stderr/异常不能打印 command/env 或解析后的凭证，元数据不能携带凭证。

### 7. CLI、默认 Profile 与延期台账

不改 ToolListCommand/InitCommand。tool list 显示声明，不是已注册/可执行证据。
init 默认 Profile 含尚未实现的 Memory 名称：本节用独立测试 Profile 验收；默认 Profile 使用时
明确报未知工具，不创建假 Memory、不自动重写用户配置；第 22 节补齐此过渡缺口。

| 跟踪项 | 后续归属与完成条件 |
|---|---|
| DR-001 | 第 22 节：save_memory/recall_memory 注册、默认 Profile 回归、记忆 Demo |
| DR-002 | 第 24 节：三值动作真实白名单、生产放行及 MCP/Java 安全边界复核；同步课件四值偏差 |
| DR-003 | 核心九 Tool 后必须补 edit_file/grep/glob/ask_user/web_search；先同步 TechnicalSolution、AGENTS 与实施计划，再单独验收 |

## Project Structure

### Documentation (this feature)

```text
specs/005-tool-system/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── tasks.md
├── contracts/
│   ├── tool-api.md
│   ├── builtin-and-sandbox.md
│   └── mcp-stdio.md
└── checklists/requirements.md
```

任务清单见 [tasks.md](tasks.md)，用户已批准并进入实现；勾选仍以逐项验证为准，未完成门禁不得封板。

### Source Code (planned, repository root)

```text
pom.xml                                      # MCP/Schema 锁版，集成排除组可覆盖
oryxos-core/src/main/java/com/oryxos/core/
├── tool/{OryxTool,ToolResult}.java            # 说明/兼容 retryable
├── react/{ToolExecutor,PromptBuilder}.java   # 授权、重试、审计、精确声明
└── config/CoreEngineConfiguration.java       # 既有 Map 注入位
oryxos-tool/pom.xml                           # MCP + Schema validator
oryxos-tool/src/main/java/com/oryxos/tool/
├── ToolRegistry.java
├── AnnotatedToolAdapter.java
├── ToolConfiguration.java                   # 包私有装配
├── ToolArgumentValidator.java               # 包私有 Schema helper
├── builtin/{FileTools,ShellTools,HttpTools,NotifyTools}.java
├── mcp/{McpClientService,McpToolAdapter}.java
├── mcp/{McpServerConfig,ConfiguredStdioTransport}.java  # 包私有
├── mcp/SchemaPreservingMcpJsonMapper.java     # 包私有，DTO前捕获原始Schema
├── sandbox/{Sandbox,SandboxAction,ActionType,SandboxViolationException}.java
└── notify/WebhookNotifyAdapter.java          # 保留 19 节 API
oryxos-tool/src/test/java/com/oryxos/tool/      # 课件七类 + 注解/通知/装配/stdio
└── sandbox/PermissiveSandbox.java             # 仅测试，非生产 Bean
oryxos-core/src/test/java/com/oryxos/core/     # 结果/Executor/PromptBuilder 回归
oryxos-boot/src/test/java/com/oryxos/boot/ToolSystemIntegrationTest.java
```

**Structure Decision**: 新增 public 生产类型仅课件交付物。配置载体、Schema helper、SDK 子类和
装配为包私有基础设施；core 不反向依赖 tool，storage 生产源码不改。

**前序触碰清单**: core 上列五文件、WebhookNotifyAdapter、相关测试、父/Tool POM。
不改 SessionManager、CLI 命令、Profile 字段、Provider API、ReActLoop 公共接口或数据库。
原由另一任务修改的 POM、CLI 测试、JpaSessionManager、OWASP suppression 全部保留；
用户确认同步完成后纳入同一归档提交，安全版本的后续修复由本会话完成并重新全量验证。

## Delivery & Validation

1. 重新运行 dependency:tree。先用本地 Java stdio fixture 证明 initialize/list/call/close、编码和
   环境隔离；连通性未过不展开 MCP 业务实现。
2. 契约/执行器 harness 先行，再实现注册/注解。每组工具先写或伴随测试，默认拒绝与正常替身都测。
3. MCP 业务接入、单服务原子注册、失败关闭，随后验证真实 Spring 快照装配。
4. 假 LLM → ReAct → 工具 → SQLite → 最终响应端到端用例，前序全部测试回归。
5. analyze → implement → verify → analyze；最终由Spark运行未跳过OWASP的完整门禁。
   未有全量证据不宣称本节完成；提交/推送由用户决定。

课件七类必须存在且非空：OryxToolContractTest、ToolRegistryTest、FileToolsTest、ShellToolsTest、
HttpToolsTest、McpClientServiceTest、McpToolAdapterTest。另加 AnnotatedToolAdapterTest、
NotifyToolsTest、SchemaPreservingMcpJsonMapperTest、装配/默认拒绝测试、本地 stdio 冒烟、
Boot SQLite 端到端测试。
已有 ToolExecutorTest/PromptBuilderTest 增补回归；方法英文名 + 中文 @DisplayName。
课件三项非空断言、精确子集、失联不炸且其余工具存在、HTTP 越界抛错等不删不弱化。
SDK 无 checked throws 时，以传输运行时异常包装 ConnectException cause 保留失联守点。

父 POM 计划让 test.excludedGroups 构建属性承载现有 integration 排除值，默认不变；显式冒烟时
覆盖为不存在的组名并选择 integration，防止只加 groups 却零用例。这不是应用配置键。
可复制的验证步骤见 [quickstart.md](quickstart.md)。

**测试执行模型（用户最新要求）**：主模型负责实现、修复及开发阶段单测/局部集成；T055/T056/T058
最终回归、快速门禁与完整门禁使用 `gpt-5.3-codex-spark`。Spark只执行给定命令，保留快照标识、退出码、
用例数、报告路径及失败摘要，不修改代码、依赖或抑制规则。最终回归失败由主模型修复后交Spark
复跑；Spark不可用时报告并暂缓最终回归，不静默换模型。早期Spark证据保留。

## 审查修复记录（2026-08-26）

| ID | 设计修复 | 实施验收任务 |
|---|---|---|
| I1 Schema 丢字段 | DTO 前保存完整原始 Schema，投影仅供 SDK 兼容；原始约束参与校验 | T035–T039、T041、T043 |
| U1 无界分页 | 单页 API + 游标循环检测 + 32页/1000工具/30秒总预算 + 迟到结果不提交 | T038、T042、T043 |
| I2 装配测试后置 | T046 同时先写 ToolConfigurationTest 的安全/顺序/冻结断言；T051仅追加core注入回归 | T046、T049、T051 |
| C1 Shell 命令缺失 | quickstart 补单独 Shell integration 命令及真实退出证据要求 | T024、T034、T055 |

本表记录设计期修复；对应实现与测试状态以tasks.md和verification.md中的实际证据为准。

## Complexity Tracking

无宪法豁免或模块演进。下列是已确认偏差、兼容处理及一项经用户批准的定点静态分析例外，
不构成新增产品范围，也不修改全局检查规则。

| 偏差 | 依据 / 处理 |
|---|---|
| String JSON 接口不同于课件示意 | 保留前序公开 API，Provider 无需重写 |
| 三值 ActionType | 技术方案 §6.7 + 本次决定；DR-002 同步课件 |
| Permissive 不在 main | 用户确认 + 宪法 VI；测试放行不等于生产许可 |
| tool list 非运行时视图 | 保留 18 节轻命令；harness 证明真实注册 |
| SDK 内部仍依赖 Reactor | 本地依赖事实；应用不引入响应式编排 |
| 本节七个而非九/十四个 | Memory 归 22；用户明确五个扩展延期但必须补 |
| Shell原始bash -c触发COMMAND_INJECTION | 这是FR-006要求的显式命令执行而非固定命令拼接；调用前Sandbox强制校验、生产默认拒绝，真实命令白名单归DR-002；仅方法级SuppressFBWarnings，Tool模块增加项目既有spotbugs-annotations的provided声明 |
| ToolExecutor设为final | 执行器不是扩展点，来源扩展统一经OryxTool；用于消除构造失败时半初始化对象的finalizer风险，公开构造/execute/审计端口签名不变，仓库无子类 |
