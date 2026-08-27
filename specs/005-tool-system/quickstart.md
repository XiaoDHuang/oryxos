# Quickstart: 第 20 节验证指南

本特性本地实现、Spark最终回归与快速门禁已全绿；完整未跳过OWASP的verify仍等待另一任务。
实际结果以tasks.md与verification.md为准，不能把本指南命令或预期输出当作外部T058证据。
当前分支：020-lesson20-tool-system。

## 前置条件

- JDK21、项目已有 Maven、Specify CLI0.14.2；Linux 实际 Shell 冒烟需 bash。
- 无需真实 API key、企业 MCP、云服务或 npx；使用临时目录、本地假 HTTP、Java stdio fixture。
- 本节验证 Profile 只声明已注册的七个工具或对应测试工具。init 默认 Profile 含尚未实现的
  save_memory/recall_memory，不可据 tool list 输出认为其已实现；等第22节补齐。
- 生产默认拒绝外部动作，测试只在测试配置显式放行。真模型/真 MCP 演示须等真实安全接线，
  不以修改生产装配为 PermissiveSandbox 来完成演示。
- 实施阶段已验证JDK21/Maven可运行；历史探测阻塞已解除，不接管另一任务OWASP。
- 本机Windows的短路径TEMP会导致JDK HTTP客户端本地套接字初始化失败。开发与最终回归命令
  可追加 `"-Djdk.net.unixdomain.tmpdir=D:/project/AI-Coding/OryxOs/.verification/lesson20"` 指向有效目录；
  首次先创建该目录，避免clean删除套接字目录或正在写入的验证日志。
  这是测试JVM环境修正，不跳过测试、不改变生产网络逻辑或断言；实际执行参数须随证据记录。

**执行模型**：主模型负责实现、修复及开发阶段验证；仅最终回归与快速门禁（T055/T056）交给
`gpt-5.3-codex-spark`。Spark只跑指定命令并返回代码快照、退出码、数量、报告路径及失败摘要，
不改代码/依赖/抑制规则。日志留本地，失败由主模型修复后再交Spark复跑；Spark不可用时暂缓
最终回归并告知用户。此分工不修改OryxOS Profile/provider，也不授权重复运行OWASP。

## 1. 实施前依赖和 MCP 最小连通性

在仓库根目录 PowerShell 执行：

```powershell
mvn -version
mvn -pl oryxos-tool -am dependency:tree "-Dincludes=org.springframework.ai:*,io.modelcontextprotocol.sdk:*,com.networknt:*,com.fasterxml.jackson.core:*"
```

依赖声明落地后的预期：Spring AI1.1.8、MCP0.18.3、networknt2.0.0；Jackson 等沿用当前父 POM
解析结果。保留真实依赖树，确认无 MCP 自动执行 starter/WebFlux transport。签名/版本不符先停，
不能随手升级依赖。此命令只运行 dependency goal，不运行 verify/OWASP。

随后先写并运行原始Schema mapper测试及本地 stdio fixture，再展开 MCP 业务实现。开发阶段先运行：

真实stdio测试需要为本次Maven进程显式设置非敏感哨兵环境变量
`ORYXOS_HOST_SENTINEL=test-host-only`，验证它没有被子进程继承；执行后恢复原值。
不要使用任何真实凭证作为哨兵，最终Spark回归也须带上此测试环境准备。

```powershell
mvn -pl oryxos-tool -am test "-Dtest=SchemaPreservingMcpJsonMapperTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

预期非零用例，从原始JSON证明allOf/$defs和对象additionalProperties完整保留，原始约束会拦截非法
参数，外部引用被拒绝，跨页不混绑且消费/失败会清理。不能只构造SDK JsonSchema DTO来证明保真。
已新增的 test.excludedGroups
构建属性默认值为 integration，显式运行时改成没有测试使用的组名：

```powershell
mvn -pl oryxos-tool -am test "-Dtest=McpStdioIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dgroups=integration" "-Dtest.excludedGroups=__none__"
```

预期实际执行非零用例：initialize/单页listTools/callTool/close、原始Schema往返，中文 UTF-8，声明 env 可见，未声明
宿主哨兵变量不可见，关闭后进程退出。启动的是测试自带 Java 进程，不是真实企业 server。
不能仅凭 BUILD SUCCESS 宣称通过，须核对 tool/target/surefire-reports 的测试数量与断言结果。
Service接入后还须复跑该suite，验证重复游标、32/33页、1000/1001工具、30秒发现总预算和迟到结果
零注册；坏服务失败后好服务仍能注册，发现线程/进程按契约退出，清理等待最多另10秒。

## 2. 本节默认 harness 与前序回归

```powershell
mvn -pl oryxos-tool -am test
mvn test
```

第一条覆盖 core/tool，第二条覆盖前序全部模块。默认排除 integration，不访问真实网络。
不可把第一条写成“前序全部模块回归”。课件方法使用英文名并保留中文 @DisplayName。

| 验收测试 | 必验内容 | 规范映射 |
|---|---|---|
| OryxToolContractTest | 遍历所有注册来源，三项非空原断言 + 唯一/非空白 | FR001/002，SC001 |
| ToolRegistryTest | 精确子集、空列表、未知/重复名、单批原子注册 | FR003，SC002 |
| AnnotatedToolAdapterTest | Schema、代理/参数绑定、普通值/ToolResult/异常、安全guard | FR004，SC005 |
| FileToolsTest | 读/写/列目录，先enforce，拒绝零IO | FR005，SC003 |
| ShellToolsTest | bash语义、30秒配置、输出、超时/中断回收、拒绝零启动 | FR006，SC003/009 |
| HttpToolsTest | GET/POST、2xx/3xx/4xx/5xx、无自动重定向、有界读取、安全先行 | FR007，SC003/009 |
| NotifyToolsTest | 默认首项、type精确匹配/歧义、缺配置、先enforce后send | FR008，SC003 |
| McpClientServiceTest | 配置、单服务失败不炸、好工具仍注册、坏批次零新增、close | FR009/010，SC004 |
| McpToolAdapterTest | 描述/Schema语义、参数保真、isError、多种content、授权和安全 | FR009/011，SC004 |
| SchemaPreservingMcpJsonMapperTest | 原始Schema保真、SDK兼容投影不外泄、约束生效、绑定清理 | FR003/009，SC004 |
| ToolExecutorTest | 首次+最多3次、失败状态审计、授权、取消、最终一次审计 | FR011/013，SC006/009 |
| PromptBuilderTest | 不静默略过未知声明、精确工具表 | FR003，SC002 |
| ToolConfigurationTest | 注册后快照、生产默认拒绝、没有自动执行链 | FR004/014，SC005/010 |
| 既有CLI/Provider回归 | tool list仍轻量；Provider schema兼容/自动工具执行禁用 | FR001/004/015，SC005/010 |

另验 ToolResult 四参构造/工厂兼容及 retryable 不变量。拒绝路径必须检查副作用计数为零，
不能只断言失败文本。计数不同：瞬态可重试最多四次实际执行，前置拒绝零IO，一逻辑调用一条审计。
ToolConfigurationTest的默认安全、MCP完成后冻结等守点在T046先写、T049跑绿；T051仅补core注入回归。

### Shell 真实进程回收（显式 integration）

在具备bash的Linux或兼容开发环境运行以下命令，最终回归阶段由Spark执行，不能用默认单测代替：

本机Windows默认bash是不可用的WSL入口；测试可将已安装的`D:/Program Files (x86)/Git/bin`
临时放在本次Maven进程PATH首位。integration的测试专用starter按PATH定位真实bash.exe，
不改生产执行命令或系统PATH。执行后恢复原PATH，并记录实际Bash版本（本机5.3.9）。

```powershell
mvn -pl oryxos-tool -am test "-Dtest=ShellToolsTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dgroups=integration" "-Dtest.excludedGroups=__none__"
```

报告必须显示非零integration用例，覆盖真实输出、非零退出、短预算超时/中断后父子进程退出和
管道读取线程结束；生产30秒/1MiB阈值保持原值，短预算仅为包内测试接点。默认单测另验30秒配置
与无bash失败路径。环境没有bash或用例被跳过时明确记为未验证，不能用mock或零用例BUILD SUCCESS
勾选T034/T055。MCP/Boot/Shell三个显式suite的结果分别记录。

## 3. 真实装配与 SQLite 端到端

```powershell
mvn -pl oryxos-boot -am test "-Dtest=ToolSystemIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dgroups=integration" "-Dtest.excludedGroups=__none__"
```

测试用假 LlmGateway、显式测试安全实现和临时 SQLite，走 AgentService → ReActLoop →
已装配 Registry/ToolExecutor → 测试本地工具 → tool_invocations → 最终回复。
必须断言成功、返回失败和重试后结束的真实数据库最终行，不只验证 mock audit 被调用。
再用默认运行时安全装配验证拒绝路径，确认无文件/进程/HTTP副作用。
确认 report 非零用例且无失败；所有临时资源退出后关闭。

## 4. 快速门禁与静态核对

本任务在 OWASP 由另一任务处理期间，只能执行快速门禁：

```powershell
mvn clean verify "-Ddependency-check.skip=true"
git diff --check
rg -n 'reactor\.|CompletableFuture|newFixedThreadPool|SecurityManager|ToolCallingManager|MethodToolCallback' oryxos-core/src/main oryxos-tool/src/main
rg -n 'internalToolExecutionEnabled|toolExecutionEnabled|SchemaOnlyToolCallback' oryxos-provider/src
rg --files oryxos-tool/src/main -g '*PermissiveSandbox*'
```

快速 verify 预期 Spotless/P3C/Checkstyle/SpotBugs/FindSecBugs/PMD 与默认测试全绿，**不是完整封板**。
第一条源码搜索人工核对不得有禁用执行路径/响应式业务代码（说明注释命中不等于违规）；
Provider 搜索确认继续禁用自动工具执行；main 下 Permissive 文件匹配应为空。
还要检查构建 JAR 内容，确认测试放行实现未打包；不得删除断言、@Disabled 或放宽阈值换绿。

全量封板要求仍是未跳过插件的 mvn clean verify；等待另一任务的 OWASP 同步及完整证据，
本任务不并发启动、不修改其 suppression、不把快速门禁改名为完整门禁。

## 5. 人工项与后续节点

- DR-001（22）：两个 Memory Tool、默认 Profile 恢复完整可用集合、跨对话记忆 Demo。
- DR-002（24）：真实白名单、三值动作同步到课件、MCP/Java安全接线、通知/文件/命令/HTTP生产验收。
- DR-003（核心九个之后）：必须补 edit_file、grep、glob、ask_user、web_search；先同步事实源与计划。
- 安全接线完成后：真模型 + SKILL.md + 经批准的真MCP跑一次；Java插件跑一次；核对 SQLite 工具审计。
  tool list只证明Profile声明，不能替代运行时注册/执行/审计证据。

任务清单与实施证据见 [tasks.md](tasks.md)、[verification.md](verification.md)。本轮不自动归档或提交；
未决安全告警、最终门禁和另一任务的完整OWASP证据必须分别如实记录，未齐备不得全量封板。
