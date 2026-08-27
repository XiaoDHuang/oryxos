# 第 20 节实施证据

**状态**：本地实现、Spark最终回归、完整安全门禁与最终一致性审查均已完成；本报告可作为第20节全量封板证明。
**分支**：`020-lesson20-tool-system`。
**最新执行分工**：主模型负责实现、修复与开发阶段验证；最终回归/门禁T055/T056/T058交给
`gpt-5.3-codex-spark`（已有子代理spark_test_runner）。Spark只执行命令、不修改代码；
下列早期Spark执行记录作为历史证据保留，不改变原执行者归属。

## 已完成前置检查

- requirements.md：16/16 完成，无未完成检查项。
- 复用修复后只读一致性审查：25 条 FR/SC、60 项任务、18 组测试先行映射；四个原问题在设计中已处理。
- 保持已有九模块、默认拒绝 Sandbox、三值 ActionType 和 tool list 轻命令；不修改其他任务的代码/安全修复。
- `.gitignore` 已覆盖 Java 构建产物、IDE、本地工作区、环境凭证和日志；本切片无新增 Docker/前端发布配置。

## 环境检查（2026-08-26，Spark 执行）

| 命令 | 结果 | 退出码 |
|---|---|---:|
| java -version | OpenJDK 21.0.11 LTS，2026-04-21 | 0 |
| mvn -version | Apache Maven 3.9.16 | 0 |

本次探测可正常返回，覆盖 plan 阶段 Maven 无输出的历史状态。未执行 OWASP 或真实企业 MCP。

## 证据记录规则与完整门禁

依赖解析、红/绿测试、本地stdio、Shell回收、SQLite端到端与全仓验证按下文记录实际证据，
不能把环境检查、编译成功或报告生成目标的成功当作测试/静态门禁通过。

T058最初等待另一任务同步；用户确认同步完成并明确转入本会话后，主模型修复依赖，Spark执行当前冻结快照的完整OWASP/verify。

## T003/T004：依赖接线

父POM仅补MCP SDK0.18.3、networknt2.0.0锁定及test.excludedGroups，Tool模块补显式依赖。
已有安全版本修改保留。dependency:tree退出码0，确认Spring Web6.2.19、Spring AI1.1.8、
MCP0.18.3、networknt2.0.0。API还核实了SchemaRegistry/Schema.initializeValidators、
SchemaLoader的远程加载禁用/阻断设置、Spring同步HTTP时限/重定向接点和AopUtils代理方法选择。

## T005/T006 → T008/T009：结果与Sandbox契约

所有测试由spark_test_runner执行，HEAD为1cc586b6560e166031c57b919d6219addbc9298e，
包含本轮未提交POM与契约改动；主任务负责源码与格式修复，Spark未修改代码。

| 阶段 | 命令关键参数 | 实际结果 | 证据 |
|---|---|---|---|
| 首次前置 | core / ToolResultTest | Spotless阻断，JUnit未运行；不计作行为红测试 | target/t005-core-test.log |
| 结果契约红态 | tool -am / ToolResultTest,SandboxContractTest | core 7项，3失败/2错误；tool因reactor停止未跑 | target/t006-tool-red-combo.log |
| Sandbox红态 | tool -am / SandboxContractTest | 5项，1失败，必填字段未拒绝 | target/t006-tool-sandbox-only.log |
| 修复绿态 | tool -am / ToolResultTest,SandboxContractTest | 7+5项全部通过，失败/错误/跳过均0，退出码0 | target/t008-t009-green.log |

命令均使用mvn test、-Dtest及-Dsurefire.failIfNoSpecifiedTests=false。对应XML在core/tool的
target/surefire-reports目录。ToolResult保留旧构造/工厂并新增retryable；Sandbox前向接口就绪，
PermissiveSandbox仅在test。真实白名单和运行时装配仍未交付。

## T007/T010：参数Schema验证

红态：ToolArgumentValidatorTest 10项全部失败（API骨架主动拒绝），见target/t007-validator-red.log。
主模型实现后，由Spark执行：mvn -pl oryxos-tool -am test
"-Dtest=ToolResultTest,SandboxContractTest,ToolArgumentValidatorTest" "-Dsurefire.failIfNoSpecifiedTests=false"。
2026-08-26，退出码0；ToolResult7 + Sandbox5 + Validator10，共22项，失败/错误/跳过均0。
证据target/t010-foundation-green.log及对应模块target/surefire-reports；HEAD保持上述提交，含未提交实现。
涵盖完整组合约束、本地引用、精确数字、重复键拒绝、外部引用零请求和错误脱敏。

## T011–T022：注解基础和US1内部契约MVP（主模型执行）

以下均为2026-08-26同一HEAD加本轮未提交代码，不是最终Spark回归。

| 阶段 | 实际命令/范围 | 结果 | 本地日志 |
|---|---|---|---|
| T011红态 | tool -am test / AnnotatedToolAdapterTest | 11项，9失败，退出1 | target/t011-adapter-red.log |
| T012/T013 | tool -am test | core33+tool32，共65项全绿，退出0 | target/t013-foundation-verified.log |
| T016/T017红态 | core定向测试 | 23项，9失败/2错误，退出1；含一处新fixture嵌套Mockito stubbing错误，已修正 | target/t016-t017-core-behavior-red.log |
| T014/T015红态 | tool注册表/契约定向测试 | 7项，1失败/6错误，退出1，注册骨架主动拒绝 | target/t014-t015-registry-behavior-red.log |
| T018–T021 | mvn -pl oryxos-provider,oryxos-tool -am test | core44+storage9+provider8+tool39，共100项全绿，退出0 | target/t021-us1-green.log |

联测命令追加 -Djdk.net.unixdomain.tmpdir=D:/project/AI-Coding/OryxOs/target。
初次基础联测有6个旧Webhook测试在JDK客户端初始化时失败（Windows UnixDomainSockets.connect
Invalid argument，TEMP使用8.3短路径）；仅改变测试JVM套接字临时目录后6项全绿，见
target/t013-webhook-environment.log。未修改业务代码、测试断言或全局环境。
另有首次PromptBuilder测试误用了不存在的tools访问器，修正为既有availableTools；编译失败不计红态。

T022只读speckit-analyze：US1重点FR-001/002/003/011/013均有任务覆盖（5/5），无新增阻断、
宪法冲突或未映射任务。全特性仍25项FR/SC、60任务；真实三来源联合守点在T053，不用当前单个
注解fixture冒充全部来源已实现。Provider自动执行关闭的已有8项回归保持通过。
采用原子注册/冻结快照、精确Profile过滤、参数Schema校验、默认插件拒绝、100/200/400ms有限重试，
最终审计取真实状态且异常不重放。未提交Git，不触碰OWASP。

## T023–T034：七个内置工具（主模型执行）

日期2026-08-26；HEAD不变，包含本节未提交实现。测试均通过mvn -pl oryxos-tool -am test，
定向用例带-Dtest与-Dsurefire.failIfNoSpecifiedTests=false；不是全仓/最终Spark回归。

| 阶段 | 结果 | 日志 |
|---|---|---|
| File红态 | 7项，4失败 | target/t023-file-red.log |
| HTTP/Notify红态，File绿态 | 18项，8失败/1错误 | target/t025-t026-red.log |
| Webhook安全增量红态 | 9项，3失败；原6项保持通过 | target/t027-webhook-red.log |
| 首次HTTP实现复验 | 27项，1失败；空白body漏过校验产生1请求，已修复实现 | target/t028-t032-partial-green.log |
| Shell红态，HTTP绿态 | 13项，6失败/1错误 | target/t024-shell-red.log |
| 全部单元测试 | core44+tool76，共120项全绿，失败/错误/跳过0，退出0 | target/t034-us2-unit-verified.log |
| 真实Bash生命周期集成 | 1项通过，失败/错误/跳过0，退出0；合并UTF8输出、300ms测试预算超时、实际父子进程退出 | target/t034-shell-cleanup-verified.log |

真实进程测试显式选择-Dgroups=integration -Dtest.excludedGroups=__none__ -Dtest=ShellToolsTest。
Windows默认裸bash指向不可用的WSL（首轮集成真实失败，未跳过）；以进程级PATH首项
D:/Program Files (x86)/Git/bin选择已安装Git Bash，测试专用starter将其解析为绝对路径，生产
仍使用bash -c原命令且不切换语言。原阻塞读取在Windows管道close时拖到30秒，真实集成断言发现后
改为虚拟线程只读取已到达的字节并在关闭管道前退出读取线程，复验1.081秒完成全部断言。
日志target/t034-shell-git-bash-verified.log保留失败证据；一处新增Files import编译错误已修正，不计行为红态。
所有测试JVM沿用前述有效Unix domain socket临时目录；未更改全局PATH/系统配置或生产阈值。

T034只读speckit-analyze：FR-005/006/007/008重点覆盖4/4，正常与拒绝双路径均有任务与真实测试；
无新增宪法冲突、阻断项或未映射任务。七工具精确集合与内置拒绝的一次失败审计已验证；
生产装配T049尚未完成，真实白名单DR-002仍未交付。Memory与五个扩展未伪造注册。

## T035–T037：MCP业务前置连通门禁（主模型执行）

2026-08-26，HEAD不变。按要求先写原始Schema与Java stdio fixture测试，再实现两个包私有辅助。

| 验证 | 实际结果 | 日志 |
|---|---|---|
| 原始Schema红态 | 5项，1失败/4错误，退出1 | target/t035-mapper-red.log |
| 原始Schema及取消清理绿态 | 6项全绿，退出0 | target/t037-mapper-verified.log |
| 真实stdio环境隔离红态 | 1项失败，初始化/清单/调用已通但宿主哨兵被继承 | target/t035-stdio-environment-red.log |
| 环境隔离修复后真实stdio | 1项全绿，2.294秒，退出0 | target/t037-stdio-verified.log |

命令为mvn -pl oryxos-tool -am test，分别指定SchemaPreservingMcpJsonMapperTest、
McpStdioIntegrationTest和-Dsurefire.failIfNoSpecifiedTests=false；后者同时带-Dgroups=integration、
-Dtest.excludedGroups=__none__以及有效套接字临时目录。测试进程临时设置
ORYXOS_HOST_SENTINEL=test-host-only（非凭证），执行后恢复原值；fixture显式声明中文环境值。
实际断言initialize→单页listTools→保真Schema→callTool→close，中文参数与环境值正确、宿主哨兵
缺席、返回PID实际存活且关闭后退出。未调用真实企业MCP/LLM；SDK进程结束诊断不替代这些断言。

原始Schema按页对象身份绑定，保留allOf/$defs/对象additionalProperties与精确小数，消费移除，
最多32待消费页，取消无需等待校验锁且迟到页无法重新绑定；SDK仅得到兼容投影。
T037硬前置已过，下一步才开始McpClientService/McpToolAdapter业务实现。

## T038–T044：MCP配置、适配与有界发现（主模型执行）

| 范围 | 实际结果 | 日志 |
|---|---|---|
| Adapter红态 | 7项，6失败 | target/t039-adapter-red.log |
| Service红态 | 11项，7失败 | target/t038-service-red.log |
| MCP定向绿态 | Service11+Adapter7+mapper6，共24项全绿，退出0 | target/t042-mcp-green.log |
| 扩展真实stdio | 5项全绿，退出0，12.19秒 | target/t043-stdio-service.log |
| US3模块回归（含复核补测） | core44+tool101，共145项全绿，失败/错误/跳过0，退出0 | target/t044-us3-final-unit.log |

开发测试仍用主模型，未触发T055/T056最终Spark回归。命令/环境参数沿用前节；定向MCP用
-Dtest=McpClientServiceTest,McpToolAdapterTest,SchemaPreservingMcpJsonMapperTest，真实stdio选择
McpStdioIntegrationTest与integration组、宿主哨兵。测试覆盖32/33页、1000/1001工具、循环游标、
重复配置/工具、坏配置/env、总预算取消、迟到批次零注册、后续好服务及关闭。
真实子进程额外覆盖Service→分页→原始Schema约束→Adapter→调用，非法参数不增加远端调用次数；
循环/超限/延迟服务不留下半份工具表，发现工作线程退出，PID文件证明各子进程确实启动且关闭后退出。

T044只读一致性复核覆盖FR-009/010与SC-004及共用授权/安全/审计契约，全部有任务映射，无新增
宪法冲突或阻断项。复核发现结构化JSON中带引号/反斜杠的已知配置秘密不能靠序列化后文本替换，
已在T041范围内改为节点级脱敏并补第8项Adapter测试；首次补测有Map import编译错误，修正后
145项全绿（编译错误不计行为红态）。这里只处理既定Tool边界，不接管OWASP。
Spring生产装配、全来源联合契约、SQLite端到端与最终门禁仍待T045以后完成。

## T045–T050：Java插件与生产装配（主模型执行，2026-08-27）

| 阶段 | 实际结果 | 日志 |
|---|---|---|
| 装配行为红态 | Adapter13通过；插件/装配缺Bean，合计20项4失败/2错误 | target/t045-t046-configuration-behavior-red.log |
| 装配定向绿态 | Adapter13+插件3+装配4，共20项全绿 | target/t049-configuration-verified.log |
| US4模块回归 | core44+tool110，共154项全绿，失败/错误/跳过0，退出0 | target/t050-us4-unit-verified.log |

命令为mvn -pl oryxos-tool -am test，定向阶段-Dtest=ToolConfigurationTest,JavaPluginRegistrationTest,AnnotatedToolAdapterTest；
带有效套接字目录及定向无匹配模块选项。测试上下文的显式MCP替身先于默认配置加载，以符合
ConditionalOnMissingBean的定义顺序；未开启Bean覆盖或放宽任何断言。首次新增测试有java变量遮蔽包名的
编译错误，修正为javaExecutable后才取得行为红态。

T050只读speckit-analyze：FR-004/014与US4验收有任务/测试覆盖，无新增阻断或宪法冲突。
JDK/类代理经过代理本身调用，returnDirect/resultConverter不启用框架执行；默认Java插件guard拒绝。
生产装配先元数据筛选Bean再实例化候选，避免递归创建等待toolTable的消费者；内置注解注册→MCP发现
→冻结→不可变Map发布已验证，显式测试许可仍只在测试接点。默认Sandbox阻止实际MCP测试进程启动，
完整Schema和参数校验回调已接线，关闭回调被执行。Core消费者限定注入与Boot端到端留给T051以后。

## T051–T054：核心注入、轻命令与SQLite端到端（主模型开发验证）

2026-08-27；同一HEAD加本轮实现，尚非最终Spark回归。

| 范围 | 实际结果 | 日志 |
|---|---|---|
| core精确注入红态 | 7项2失败：缺Qualifier且无tool模块时误注入decoy Map | target/t051-core-injection-red.log |
| 注入/三来源/CLI绿态 | 装配7+真实三来源契约9+CLI2，共18项全绿，退出0 | target/t052-t053-injection-cli-green.log |
| Boot SQLite端到端 | 2项全绿，退出0，10.87秒 | target/t054-sqlite-e2e-verified.log |

核心两处Map注入位增加Qualifier(toolTable)，公开签名和无模块空表能力保留。CLI两个新测试不修改
另一任务的OryxOsCliHelpTest；声明未知运行时工具仍可列出，Spring启动/进程构造均为零。
三来源参数源现包含七个实际内置工具、Java Adapter与mock同步客户端支撑的实际MCP Adapter。

端到端使用Boot自动配置的真实SQLite/JPA、真实schema.sql、实际ToolConfiguration/Registry、
CoreEngineConfiguration装配的AgentService/ReAct/ToolExecutor、真实FileTools与JpaToolInvocationAudit。
只有LlmGateway和无IO瞬态测试工具为受控替身。成功写文件、读取失败、首次+三次重试恢复三种
逻辑调用直接SQL查询各一条最终审计，状态completed/failed/completed，重试总耗时至少700ms；
会话4条历史从SQLite重新读出。另一个上下文未注入Sandbox，默认拒绝并落一条failed，文件未创建。
MCP替身的Bean定义在上下文刷新前登记，不开启Bean覆盖；初次测试上下文重名失败已修正测试装配。
端到端命令：mvn -pl oryxos-boot -am test -Dtest=ToolSystemIntegrationTest
-Dsurefire.failIfNoSpecifiedTests=false -Dgroups=integration -Dtest.excludedGroups=__none__，另带有效套接字目录。

进入T055前把开发阶段target/t*.log复制到.gitignore已覆盖的.verification/lesson20/，防止clean清掉证据；
旧表格中的target日志均可按同名在该目录找到。最终Spark日志直接写入此目录。

## T055/T056：第一轮Spark回归与门禁修复过程（历史证据）

第一轮由gpt-5.3-codex-spark执行，HEAD同上，146个源码/构建输入文件的快照SHA256：
f9c6a33a914386e421543409ba1850eee80353d5fda79e0326149195cc69072b。

| 阶段 | 实际结果 | 日志（.verification/lesson20/） |
|---|---|---|
| 全仓mvn test | 190项全部通过，失败/错误/跳过0，退出0 | t055-spark-default.log |
| 显式integration | Shell1+MCP5+Boot2，共8项全绿，退出0 | t055-spark-integration.log |
| 快速clean verify，OWASP跳过 | 退出1，core44测试通过后被8处SummaryJavadoc格式问题阻断；不是完整通过 | t056-spark-fast-verify.log |

主模型随后做开发阶段编译/静态诊断（没有代跑最终回归）：修正Javadoc与显式导入、行宽/常量/
条件复杂度，按职责拆分Shell输出读取和MCP等待/清理；生产时限、重试上限与全部断言保留。
ToolExecutor设为final以消除构造失败时半初始化对象的finalizer风险，既有构造/execute签名不变，
仓库无继承者。目录项名称增加空值防御，URL协议使用ASCII大小写匹配，MCP Adapter仅持有RPC
调用函数而不持有连接管理能力；没有新增全局抑制。HTTP增加600不重试断言；Shell补真实非零退出
和中断回收测试，下一轮显式integration应为Shell2+MCP5+Boot2=9。

最后一次core/tool开发静态检查的P3C和Checkstyle通过，SpotBugs/FindSecBugs仅余一项：
**GATE-001：ShellTools.shell的COMMAND_INJECTION（原始bash -c设计入口）**。
源码仍在ProcessBuilder之前执行Sandbox，生产默认拒绝；尚未添加抑制。已向用户请求是否允许
仅为该入口添加带安全依据的定点抑制，不改全局规则。未经答复不擅自处理，不将T056勾为完成。
证据dev-tool-static-final.log及oryxos-tool/target/spotbugsXml.xml。

第二轮源码已冻结，146文件SHA256：80c171d35f3993c12fdaa61ef635369ddea405a21b1406047f7fb6ce0d3f411c；
Spark按更新源码复跑完成：默认190项、显式Shell2+MCP5+Boot2共9项全部通过，失败/错误/跳过均0，
两条命令退出码均0，快照一致。日志t055-spark-default-r2.log、t055-spark-integration-r2.log；
T055已完成。T056未复跑：GATE-001仍待用户决定；最新单项FindSecBugs报告另存findsecbugs-current.log。

## H4与交付物预核对（T057尚待完整快速门禁及JAR检查）

| 不变量 | 当前证据 |
|---|---|
| 工具IO先过Sandbox | File/HTTP/Notify/Shell/MCP执行与启动点均有enforce；拒绝零IO测试已存在 |
| LLM/Tool成败落库 | ProviderService两条状态路径接LlmCallAudit.repository.save；ToolExecutor最终一次record接Jpa审计，SQLite端到端已验证 |
| 无明文key | 变更main源码扫描无匹配的sk-凭证，配置仍用环境变量；未在结果/日志注入MCP配置秘密 |
| Session ID唯一生成点 | 三元组拼接仍只在JpaSessionManager，Tool模块无Session ID生成逻辑 |
| 同步/虚拟线程 | main无Reactor/CompletableFuture/固定线程池；辅助虚拟线程仅用于有界IO与生命周期 |
| 无框架自动工具执行 | Provider internalToolExecutionEnabled(false)保留；注解Adapter自行调用，无ToolCallingManager执行路径 |

九模块列表未变，新增public类型均在本节交付物内；七类课件harness均存在且已执行非零用例。
main中没有PermissiveSandbox；本轮最终JAR检查仍待T056后完成。git diff --check通过。
DR-001/002/003全部保留；此阶段T058仍待完整证据，后续修复与复验记录见报告末尾。

## T061–T066：实现一致性修复（2026-08-27）

一致性审查发现6项可执行缺口并由speckit-converge追加Phase 8。T061–T064先补测试：
ToolConfiguration/Shell/MCP Service/MCP Adapter共38项，4项按预期失败，见
.verification/lesson20/t061-t064-behavior-red.log；实现后38项全绿，见t061-t064-green.log。

- T061：ToolConfiguration改为Boot AutoConfiguration并登记imports；默认拒绝与用户Sandbox优先均验证，
  Boot SQLite测试改为显式加载真实自动配置，不再靠配置类声明顺序回避Bean冲突。
- T062：Shell在进程退出后等待3次、每次10ms的稳定空窗口；测试模拟available连续2次为0后出现
  tail，证明不再单次为0即丢尾部；30秒/1MiB与清理上限未变。
- T063：不加配置键，按KEY/TOKEN/SECRET/PASSWORD/CREDENTIAL/AUTH键名识别敏感env；MODE=a
  不再误伤data_tool，API_KEY仍进入秘密集合，规则已同步mcp-stdio契约。
- T064：结构化JSON字段名含已知秘密时失败关闭；文本值脱敏仍保留。
- T065：用户批准原始bash -c的COMMAND_INJECTION单方法静态例外；调用前Sandbox与生产默认拒绝不变，
  DR-002承接真实命令白名单。Tool模块仅增加项目既有spotbugs-annotations的provided声明；
  全局SpotBugs/FindSecBugs配置和阈值未改。
- T066：ToolExecutor明确为非扩展点，扩展只走OryxTool；仓库无子类，公开构造/execute/审计签名不变。
  spec/plan阶段状态和历史“全部待实现”文字已同步。

主模型开发静态命令 `mvn -pl oryxos-tool -am test-compile pmd:check checkstyle:check spotbugs:check`
退出0，P3C/Checkstyle/SpotBugs/FindSecBugs全绿，见t065-dev-static-green.log。
首次显式集成使用2秒测试预算时，正常Java fixture在2.011秒完成而超出11ms；按oryxos-lesson-dev
停止并获用户明确批准后，只把测试发现预算改为3秒。生产30秒、slow fixture 4秒、清理2秒及外层
10秒断言不变。复验Shell2+MCP5+Boot2共9项全绿，见t061-t064-dev-integration-green.log。

T061–T064、T066已完成；T065代码/文档/开发静态检查完成，仍等Spark最终T056后才勾选。
该阶段OWASP和另一会话文件尚未接管；后续用户确认转入本会话并完成T058。

## Spark r3最终回归与快速门禁（T055/T056，2026-08-27）

最终冻结快照：HEAD `1cc586b6560e166031c57b919d6219addbc9298e`，147个源码/构建输入文件，
SHA256 `5df506822589fa1c2cd87c5e7b5c96e8667c1ea1ce4f387a71a275fa0938aade`；Spark开始/结束核对一致。

| 阶段 | Spark实际结果 | 日志（.verification/lesson20/） |
|---|---|---|
| 全仓默认回归 | 195项，失败/错误/跳过0，退出0 | t055-spark-default-r3.log |
| 显式integration | Shell2+MCP5+Boot2，共9项全绿，退出0 | t055-spark-integration-r3.log |
| 快速clean verify | 195项全绿，退出0；Spotless、P3C、Checkstyle0、PMD、SpotBugs/FindSecBugs0全部通过 | t056-spark-fast-verify-r3.log |

快速门禁按授权使用`-Ddependency-check.skip=true`，日志明确`Skipping dependency-check`；因此T056完成，
但它不是T058要求的完整未跳过verify。Shell单点COMMAND_INJECTION例外生效且全局规则/阈值未改，
T065完成。完整快照记录见lesson20-snapshot-r3.log与lesson20-snapshot-r3-end.log。

## T057：H4、交付物与JAR核对

- 任何Tool实际IO前均有Sandbox.enforce；File/Shell/HTTP/Notify/MCP拒绝零IO测试保留。
- Provider成功/失败均调用LlmCallAudit并由repository.save落库；ToolExecutor最终一次record接Jpa审计，
  SQLite端到端同时覆盖成功、失败、重试恢复。
- main源码未匹配`sk-`形式明文凭证；MCP环境仅声明项进入子进程，敏感键值不进元数据/结果。
- session_id三元组仍只在JpaSessionManager拼接。
- 应用main无Reactor、CompletableFuture、固定线程池、SecurityManager；辅助执行使用Java21虚拟线程。
- Provider仍`internalToolExecutionEnabled(false)`，main无ToolCallingManager/MethodToolCallback执行路径。
- 父POM仍为9模块；新增public类型均属于第20节清单，包私有helper/配置未外泄。
- 课件七类及扩展harness全部存在、非空且无`@Disabled`。
- 2026-08-27重新构建的oryxos-tool JAR为68508字节，包含AutoConfiguration.imports、
  ToolConfiguration、ShellTools、McpClientService；不包含PermissiveSandbox。git diff --check通过。

T057完成。真实白名单、真模型/真企业MCP仍按DR-002/人工项延期，不能据测试放行宣称生产已开放。

## T059：最终一致性分析

最终speckit-analyze核对15项FR、10项SC、4个User Story、66项任务及宪法8条原则：

- 25/25需求均有任务和实现/测试映射，覆盖率100%；无未映射任务。
- 无CRITICAL/HIGH/MEDIUM问题，无宪法冲突；此前6项实现缺口均已由T061–T066关闭。
- 仅发现两处LOW文档状态陈旧：任务统计仍为60、报告顶部仍停在T001–T054；已在T060同步。
- 本地冻结源码、构建输入和Spark开始/结束快照一致；该阶段POM/CLI/JpaSessionManager/
  dependency-check-suppressions仍按原归属保留，后续经用户确认后合并验证。

T059完成。该阶段本地实现已收敛，但当时T058证据仍缺失；最终状态以末尾T058复验为准。

## T060：六项DoD与交付摘要

| DoD | 证据 / 状态 |
|---|---|
| 完整clean verify | Spark T058 r2退出0；195项测试、Spotless/P3C/Checkstyle/PMD/SpotBugs/FindSecBugs全绿，OWASP在10个reactor项目实际执行且0漏洞依赖 |
| Harness | 课件七类及扩展共12个测试类全部存在、非空、无Disabled；默认195项、显式integration9项全绿 |
| 交付物 | OryxTool/ToolResult、Registry/Adapter、七内置、MCP、Sandbox前向契约、自动配置、CLI/Boot回归均存在；九模块未变 |
| 前序回归 | `mvn test`覆盖全部九模块并由Spark执行，195/0/0/0 |
| H4六项 | Sandbox先行、双审计落库、无明文key、Session ID唯一拼接、无应用响应式/线程池、自动Tool执行关闭，均已逐项核对 |
| 人工/外部项 | T058已完成；DR-001 Memory、DR-002真实白名单、DR-003五扩展；安全接线后真模型/真webhook/批准的真MCP与Java插件冒烟 |

### 变更导读

- `oryxos-core`：9个文件，兼容扩展ToolResult、精确工具表、授权/重试/最终审计与core注入；
  ToolExecutor明确非扩展点，公开入口签名不变。
- `oryxos-tool`：38个文件，新增Registry、注解适配、七内置、MCP stdio/Schema保真、Sandbox前向契约、
  Boot自动配置及完整harness；WebhookNotifyAdapter为第19节前序触碰。
- `oryxos-boot`：新增SQLite端到端；`oryxos-cli`新增轻量tool list测试。
- `specs/005-tool-system`：11个长期保留的Spec-Kit与验收文件。
- 原另一任务归属的父POM安全版本/OWASP suppression、OryxOsCliHelpTest、JpaSessionManager经用户确认
  同步完成后纳入本次归档；最终完整门禁覆盖合并后的实际依赖与代码。

### 重点Review位置

1. `oryxos-tool/.../ToolConfiguration.java`与AutoConfiguration.imports：用户Sandbox优先、MCP完成后冻结发布。
2. `builtin/ShellTools.java`：30秒/1MiB、稳定排空、父子进程清理，以及经批准的单方法静态例外。
3. `mcp/SchemaPreservingMcpJsonMapper.java`与`McpClientService.java`：原始Schema、32页/1000工具/30秒预算和迟到零注册。
4. `mcp/McpToolAdapter.java`：双重Profile授权、Sandbox先行、秘密值/字段名保护、不可盲目重试。
5. `oryxos-core/.../ToolExecutor.java`与Boot端到端：首次+3次退避、最终一次SQLite审计、审计失败不重放。

### 可复制验证命令

```powershell
# 预期：九模块默认测试195项全绿
mvn test "-Djdk.net.unixdomain.tmpdir=D:/project/AI-Coding/OryxOs/.verification/lesson20"

# 预期：Shell2 + MCP5 + Boot2，共9项全绿；执行前按quickstart准备Git Bash PATH与非敏感哨兵
mvn -pl oryxos-boot -am test "-Dtest=McpStdioIntegrationTest,ShellToolsTest,ToolSystemIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dgroups=integration" "-Dtest.excludedGroups=__none__"

# 预期：快速门禁全绿，但不能替代OWASP完整门禁
mvn clean verify "-Ddependency-check.skip=true"

# T058完整门禁：在当前代码/依赖快照运行未跳过任何插件的完整verify
mvn clean verify
```

T060完成；归档提交仍以用户明确指令为准，T058通过后方可执行。

## T058：当前快照完整OWASP门禁复核（失败，保持未完成）

用户确认security/OWASP同步后，由gpt-5.3-codex-spark对当前147文件冻结快照
`5df506822589fa1c2cd87c5e7b5c96e8667c1ea1ce4f387a71a275fa0938aade`执行未设置任何skip的：

```powershell
mvn clean verify "-Djdk.net.unixdomain.tmpdir=D:/project/AI-Coding/OryxOs/.verification/lesson20"
```

开始/结束快照一致，但命令退出1。Spotless、Checkstyle、P3C/PMD、SpotBugs/FindSecBugs及已执行测试
在失败点前均无违规；OWASP Dependency-Check确实运行，并在`oryxos-web`因CVSS阈值7.0阻断：

- `tomcat-embed-core-10.1.57`：`CVE-2026-68763`，CVSS 7.5；
- Swagger UI内嵌`DOMPurify@3.4.12`另记录`CVE-2026-75838`等报告项。

失败导致channel-cli/cli/boot后续模块未执行，不能用失败前190项代替完整195项门禁。证据：
`.verification/lesson20/t058-spark-full-verify.log`及
`oryxos-web/target/dependency-check-report.{html,json}`。本会话不擅自升级安全版本、增加suppression或
绕过阈值；T058保持未勾选，归档提交暂停，等待security会话修复后重新验证当前快照。

## T058：安全依赖修复与完整门禁复验（通过）

用户随后明确要求在本会话修复。依据上游已发布修复版本，将Tomcat从10.1.57升级到10.1.59，
Swagger UI WebJar从5.32.11升级到5.32.14（其DOMPurify依赖已为3.4.13）；未新增suppression，
Dependency-Check阈值和插件配置均未放宽。依赖树确认`oryxos-web`实际解析上述版本。

由`gpt-5.3-codex-spark`对147个源码/构建输入文件的冻结快照执行未设置任何skip的：

```powershell
mvn clean verify "-Djdk.net.unixdomain.tmpdir=D:/project/AI-Coding/OryxOs/.verification/lesson20"
```

- 开始/结束快照均为`e5e31d0d351623307640880b84cf52be868c761757eea7e2448394b06792ec2e`，逐文件差异0；
- 10/10 reactor模块成功，195项默认测试失败/错误/跳过均为0；
- Spotless、Checkstyle、P3C/PMD、SpotBugs/FindSecBugs门禁通过；
- Dependency-Check在父项目及九模块共实际执行10次，无skip；`oryxos-web`报告87个依赖、0个漏洞依赖、0个suppression命中；
- 命令退出0，`BUILD SUCCESS`，总耗时7分28秒。

证据：`.verification/lesson20/t058-spark-full-verify-r2.log`、
`lesson20-snapshot-t058-r2-start.log`、`lesson20-snapshot-t058-r2-end.log`及各模块
`target/dependency-check-report.{html,json}`。T058完成，第20节全部66项任务满足封板条件。
