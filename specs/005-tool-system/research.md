# Research: 第 20 节统一 Tool 体系

**Date**: 2026-08-26 | **Method**: 课件/事实源、当前源码、本地 Maven POM 与 javap，只读核实。

本轮不联网、不启动真实 MCP、不运行 OWASP。已核实仅指源码/签名，不等于运行兼容性通过。
设计选项已落定，实施前的运行门禁独立记录，不冒充已完成。

## R1 — JSON 文本接口与结果兼容

**Decision**: 保留 OryxTool 的 String schema/arguments；ToolResult 加 retryable，保留四参构造和旧工厂。

**Rationale**: 当前 core 接口已被 Provider、ReAct 与测试消费；本节明确补齐结果语义，课件代码为示意，
技术方案未强制 Java JSON 类型。旧失败默认不可重试，成功永不可重试。

**Alternatives considered**: JsonNode 替换会破坏前序 API；另造结果类型会分裂统一契约。

## R2 — 授权、有限重试和一次最终审计

**Decision**: 首次 + 最多 3 次，100/200/400ms 退避；最终一条审计。未授权、未知、缺上下文、参数/
Sandbox/4xx 不重试；中断停止；无法确认可安全重放的副作用默认不重试。

**Rationale**: 用户已区分 ReAct 十轮和工具三次重试。当前 ToolExecutor 只查全局名字，且把返回
ToolResult.fail 也记成 success=true，必须随接线修正。审计放在重试之外，避免审计失败重放工具。

**Alternatives considered**: 每尝试落行偏离一逻辑调用一审计；盲重试 RuntimeException 会重放 Shell/
POST/notify。不新增 retry_count、事件表或重试框架。

## R3 — 完成注册后发布 Map

**Decision**: 全部注解/MCP 注册完成再提供 toolTable Bean；core 只依赖 Map，prompt 和实际执行双检查。

**Rationale**: PromptBuilder/ToolExecutor 构造器均 Map.copyOf，启动后注册将丢失工具。现有装配已有 Map 位。

**Alternatives considered**: core 依赖 Registry 会反转模块依赖；可变全局表带来无必要的热更新并发；
仅过滤 prompt 无法阻止模型构造未授权调用。

## R4 — 注解只生成 Schema，反射由 OryxOS 控制

**Decision**: Spring AI 注解/SchemaGenerator + Jackson + 本地 networknt 校验，自行反射执行同步方法。

**Rationale / verified API**: 本地 spring-ai-model:1.1.8 javap 确认：

```text
Tool.name(), description(), returnDirect(), resultConverter()
ToolParam.required(), description()
JsonSchemaGenerator.generateForMethodInput(Method, SchemaOption...) -> String
```

@Tool 没有 readOnly/idempotentHint，不能把 MCP 注解属性移植过来。父 POM 已有 parameters=true。

**Alternatives considered**: MethodToolCallback.call 让框架承担执行，违反宪法 II；手写所有 Schema 容易漂移。

## R5 — 三值 Sandbox，默认拒绝

**Decision**: FILE_ACCESS/SHELL_EXEC/HTTP_REQUEST；Permissive 只进 test。MCP 启动也检查，
Java 插件无明确安全接线则生产不可调用。

**Rationale**: 用户继续已提出的三项建议；遵循技术方案与宪法。真实白名单仍交第 24 节。

**Alternatives considered**: 生产 permissive 只有形式调用没有保护；新造 PLUGIN/MCP 动作超出契约；
用 Shell 白名单冒充任意 Java 代码隔离不成立。

## R6 — 内置边界与内部资源限额

**Decision**: 本节七个、Memory 归 DR-001、五个扩展归 DR-003。Shell 30 秒；HTTP 连接5秒/读取30秒；
HTTP body/Shell 输出各1MiB，超限失败，HTTP 不自动重定向。通知复用第19节契约。

**Rationale**: Shell30秒已确认；其余数值是 plan 的实现选择，不冒称原文数字，不增加配置面。

**Alternatives considered**: 无限缓冲/阻塞不可取；把 Web60秒当 Shell 重试预算混淆边界；新增大量 YAML 旋钮超范围。

## R7 — MCP 0.18.3，同步 stdio

**Decision**: tool 显式依赖 io.modelcontextprotocol.sdk:mcp:0.18.3，不引入自动执行 starter。

**Rationale / local evidence**: 本地 spring-ai-mcp:1.1.8 POM 明确依赖0.18.3，仅用它作版本证据。
mcp/mcp-core/mcp-json-jackson2 的0.18.3 jar 均存在，API 实际在 mcp-core。已 javap：

```text
ServerParameters.builder(String).args(List<String>).env(Map<String,String>).build()
StdioClientTransport(ServerParameters, McpJsonMapper)
StdioClientTransport.setStdErrorHandler(Consumer<String>)
McpClient.sync(McpClientTransport)
  .requestTimeout(Duration).initializationTimeout(Duration).build() -> McpSyncClient
McpSyncClient.initialize(), listTools(), listTools(String cursor)
McpSyncClient.callTool(McpSchema.CallToolRequest), close(), closeGracefully()
McpSchema.CallToolRequest(String name, Map<String,Object> arguments)
io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper(ObjectMapper)
```

无参 listTools 字节码会展开 nextCursor，但没有游标循环/总量保护；实施改用已核实的
listTools(String cursor) 单页接口，具体边界见下文 R11。callTool 返回对象，需处理 isError/
content/structuredContent。
审查 I1 更正了原结论：SDK JsonSchema 只有 type/properties/required/additionalProperties/
defs/definitions 六项，且标记 JsonIgnoreProperties(ignoreUnknown=true)；直接重序列化会丢失根级
allOf/$schema 等字段，additionalProperties 还是 Boolean，不能承载原始 Schema 对象。
因此它只能作 SDK 内部兼容投影，不能作为 OryxTool 参数说明或校验的事实来源。
McpSyncClient 无 checked ConnectException throws，测试用传输运行时异常包装其 cause。
closeGracefully 内部最多等10秒并返回 boolean；失败仍 close。编码/超时包装/资源回收须运行 fixture。

**Alternatives considered**: 不选旧缓存0.7.0或零散0.17.0；不存在课件示意 McpClient.connect。
不选 SSE、WebFlux transport 或异步应用客户端。

**原始 Schema 保留路径（本地签名与调用链已核实）**：McpJsonMapper/TypeRef 实际位于
mcp-core:0.18.3，不是独立 mcp-json:0.18.3 jar。StdioClientTransport.unmarshalFrom(Object,TypeRef)
直接调用注入 mapper 的 convertValue(Object,TypeRef)；TypeRef.getType() 可识别 ListToolsResult。
新增包私有 SchemaPreservingMcpJsonMapper 在这个转换点复制原始工具列表/完整 inputSchema，
验证原始数据后只向 SDK DTO 转换兼容副本，并按返回页对象身份保存完整 Schema 绑定。
服务消费绑定后立即移除；最多32个待消费页，取消/失败/冻结全部清理，未受控的刷新清单不得发布。
Mapper 其余 readValue/convertValue/writeValueAsString/writeValueAsBytes 方法委托既有 Jackson mapper。
该委托使用独立配置，并从首次JSON读取起保留精确数字、拒绝重复键，不能在已经损失数据的Map上
事后恢复；对应边界纳入T035，具体Jackson配置API在T004实施前复核。

SDK兼容副本的inputSchema只保留可表达字段，additionalProperties为对象时不强转Boolean；
完整值仍保存在原始副本。显式 enableCallToolSchemaCaching(false)，原始 Schema 的校验由统一
ToolArgumentValidator 完成，SDK投影不参与授权、Provider发布或参数检查；缺原始绑定必须失败。
该路径需要T035–T039原始JSON→真实mapper→适配器回归证明，本轮只核验API，不宣称已运行。

## R8 — 主动收紧 MCP env 继承

**Decision**: 包私有 ConfiguredStdioTransport override getProcessBuilder，清空 builder.environment，
随后仅由 SDK 注入它的最小系统变量和该服务声明 env。仅 env 值解析占位符，键保留大小写。

**Rationale / bytecode evidence**: 默认 getProcessBuilder 返回 new ProcessBuilder；connect 只 putAll，
不 clear，因此仅传 .env 并不能阻止继承宿主全部凭证。ServerParameters 默认系统集合已核实：

- Windows：APPDATA/HOMEDRIVE/HOMEPATH/LOCALAPPDATA/PATH/PROCESSOR_ARCHITECTURE/
  SYSTEMDRIVE/SYSTEMROOT/TEMP/USERNAME/USERPROFILE。
- 非 Windows：HOME/LOGNAME/PATH/SHELL/TERM/USER。

测试以非系统宿主哨兵变量证明未声明密钥对子进程不可见。不经过 ProfileLoader 的键名归一化。
getEnv 虽返回可变 Map，但本计划不依赖清空 SDK 内部 Map 的技巧。

**Alternatives considered**: 全继承违背敏感变量只交声明服务；完全空环境影响 PATH/SystemRoot；
新增 inherited_env 配置不在范围。

## R9 — Schema 验证器与外部引用

**Decision**: 显式用 com.networknt:json-schema-validator:2.0.0（MCP mapper 已用的同版本），
包私有 helper 集中校验；支持本地方言和片段引用，拒绝外部引用。root包的ToolConfiguration
通过JDK Consumer<String>/BiConsumer<String,String>把Schema/参数校验行为交给mcp子包，
不将ToolArgumentValidator改public，也不复制一套校验器。

**Rationale / verified API**: 本地 jar 与 SDK DefaultJsonSchemaValidator 字节码确认：

```text
SchemaRegistry.withDialect(Dialects.getDraft202012()).getSchema(JsonNode) -> Schema
Schema.validate(JsonNode) -> List<com.networknt.schema.Error>
Error.getKeyword(), getInstanceLocation(), getSchemaLocation(), getMessage()
```

2.0.0 没有旧 JsonSchemaFactory/SpecVersion 示例 API。注册前递归检查 $ref/$dynamicRef/$recursiveRef，
仅允许 # 本地引用；未知 $schema 方言拒绝，不能让注册 Schema 发外网请求。诊断不输出敏感实例值。
实际本地 refs/无网络测试仍须实施。

**Alternatives considered**: 自写全套 JSON Schema 不必要；自由解析外部 URI 会绕过 Sandbox；
将 validator 放 core 增加无关依赖。

## R10 — 保持 CLI 与前序契约

**Decision**: tool list 只读声明；本节使用独立 Profile，默认 Memory 名称不能假装已注册。

**Rationale**: InitCommand 已有 servers 根与 argv 示例以及两个 Memory 名称，ToolListCommand 不启动
Spring。第19节已定 notify 缺省取首项。ProfileContext 是静态接口，不重造可注入实例。

**Alternatives considered**: 偷改 init 配置、静默忽略未知名、把列表改重命令都会破坏约定；新增 CLI 超范围。

## R11 — MCP 发现总预算与原子提交

**Decision**: 每服务最多32页、累计1000个工具；记录已见游标，重复非空游标立即失败。
发现总预算30秒，覆盖该服务初始化、分页和清单校验；初始化自身仍限10秒，普通callTool请求限30秒。
失败资源清理最多另等10秒，清理不是发现宽限；数字均为内部常量，不加配置字段。

**Rationale / bytecode evidence**: SDK无参listTools使用expand+reduce，仅以null/空游标结束并不断
addAll；McpSyncClient无参block没有聚合截止，McpClientSession.requestTimeout只限制每个RPC。
重复游标或持续快速翻页不会触发单请求超时，因此原先的30秒请求上限不能保证启动可结束。

调用线程逐服务同步推进，用一个辅助虚拟线程进行该服务发现，Thread.join(Duration)等待剩余
总预算（本机JDK21已javap确认）；取消/超时中断并关闭client。只有调用线程能把按时完成且通过
校验的整批工具交给Registry，工作线程不注册，迟到结果不能写入。取消、退出、清理和边界情况
须在T038/T043通过短预算可控测试验证，不将生产30秒缩短来假造通过。

**Alternatives considered**: 继续无参聚合或只检查每页返回后的时钟无法及时中止正在等待的RPC；
仅靠页数上限仍可能长时间阻塞；引入响应式应用API、并发发现池或新配置面没有必要。

## 依赖与证据边界

| 依赖 | 处理 / 本地证据 |
|---|---|
| Spring AI model 1.1.8 | 沿用，tool 可补显式声明，BOM 锁定；注解/Schema API 已 javap |
| Spring Web 6.2.19 | 沿用；第19节 RestClient 代码与测试 |
| MCP SDK 0.18.3 | 计划新增并锁版；Spring AI MCP POM + javap |
| networknt 2.0.0 | 计划显式声明并锁版；mapper POM + 本地 jar/javap |
| Jackson/SnakeYAML | 沿用父 POM，不干预另一任务同步；实施重新解析组合 |
| MockWebServer 4.12.0 | 已有 test scope，第19节 harness |

2026-08-26 尝试离线 mvn -o -pl oryxos-tool -am dependency:tree，进程无输出；mvn -version
探测也无输出，已中断本轮探测会话，未触碰其他任务。故本轮无有效依赖树、编译或测试证据。
SDK POM 的传递版本不代表项目最终解析版本。

实施前必须重跑依赖树、最小 stdio 初始化/列表/调用/关闭/UTF-8/环境隔离，以及 Schema 本地引用与
阻断外部引用测试。API/依赖不匹配立即按 skill 软门禁暂停，不临时升级或加入计划外依赖。
