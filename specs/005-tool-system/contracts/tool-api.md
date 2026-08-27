# Contract: Tool 注册、执行与审计

本文件定义实现目标，不表示类/方法已落地。包根沿用 com.oryxos。

## OryxTool / ToolResult（core）

```java
String getName();
String getDescription();
String getInputSchema();
ToolResult execute(String argumentsJson);

ToolResult(String toolName, boolean success, String content, String errorMessage,
           boolean retryable);
ToolResult(String toolName, boolean success, String content, String errorMessage);
ToolResult.ok(String toolName, String content);
ToolResult.fail(String toolName, String errorMessage);
ToolResult.fail(String toolName, String errorMessage, boolean retryable);
```

以上工厂保持 static。旧四参构造和旧工厂默认 retryable=false；成功不得可重试。
普通失败以结果表达；SandboxViolationException 可从具体 Adapter 抛出以保留课件拒绝测试，
最终由 ToolExecutor 转为失败审计。禁止靠框架自动 tool 执行消费此契约。

## ToolRegistry（tool）

```java
void register(OryxTool tool);
void registerAll(List<? extends OryxTool> tools);
void registerAnnotated(Object bean);
boolean contains(String name);
List<OryxTool> all();
List<OryxTool> forProfile(Profile profile);
Map<String, OryxTool> asMap();
```

- 注册必须拒绝空对象、空白名/描述、无效对象 Schema、重复名字；已有映射不可覆盖。
- registerAll 先完整验证再提交，全批成功或零新增；用于一台 MCP 服务原子入表。
- all/asMap 返回只读快照。生产装配完成后冻结注册，冻结为包内操作，不提供热更新接口。
- forProfile 按 tools 声明顺序返回准确子集；未知名/重复声明失败，空列表返回空。
- MCP 服务失联导致工具缺席时，受影响 Profile 使用时明确失败；不能静默少给工具。
- toolTable 的生成必须在 connectAll 完成后；core 不依赖本类型。
- MCP 工具提供完整原始 Schema；不得以 SDK 的兼容投影替代。注册原始 Schema 与执行时校验
  必须使用同一份不可变数据，不得通过丢弃 allOf 等约束使参数“通过”。

## AnnotatedToolAdapter（tool）

实现 OryxTool；元数据来自 @Tool，参数 Schema 来自 Spring AI JsonSchemaGenerator。
name 缺省用方法名，description 不允许空；只接受可在实际 Bean/代理上调用的 public 同步实例方法。
拒绝 Publisher/CompletionStage/Future 等异步或响应式返回，不消费 returnDirect 改变 ReAct 终止行为。
不调用 resultConverter 或 ToolCallback 来把执行权交给框架；返回值由 OryxOS 的统一规则包装。

参数必须是 JSON 对象，必填/类型/未知字段约束按发布 Schema 校验；JSON 解析拒绝重复键。
绑定 String、数字、boolean、Map/List、可映射 DTO，非法值在调用 Java 方法前失败。
本地引用允许，外部 $ref/$dynamicRef/$recursiveRef 及未知方言拒绝；注册/校验不得联网。

返回 String 作为 content；返回其他非空普通对象用 Jackson 转 JSON；void/null 返回空内容成功。
返回 ToolResult 时验证工具名与状态一致后保留；方法抛错转换为明确失败，Sandbox 拒绝除外。
Java 插件未知异常不自动标为可重试；明确返回 retryable=true 的插件承担可安全重放声明。

内置工具由包内装配接入各自 Sandbox；普通 Java 插件的生产调用 guard 默认拒绝，测试显式供给
放行 guard。此 guard 是包内接线，不新增 public Policy/配置开关，不能拿 @Tool 代替安全审查。

包私有ToolArgumentValidator通过同包ToolConfiguration向MCP接线提供JDK Consumer<String>
与BiConsumer<String,String>，分别负责Schema和Schema/参数校验；不要求mcp子包访问包私有类，
不新增public校验服务或第二套规则。

## ToolExecutor / PromptBuilder（core，签名不变）

```java
ToolExecutor(Map<String, OryxTool> toolTable, ToolInvocationAudit audit);
ToolResult execute(String sessionId, AssistantMessage.ToolCall call);
PromptBuilder(ContextLoader contextLoader, Map<String, OryxTool> toolTable);
```

执行顺序：读取 ProfileContext → 检查声明授权和工具存在 → Adapter 参数验证 → IO 安全门 →
工具尝试/必要重试 → 最终审计 → 返回 ReAct。

缺当前 Profile、未授权、未知名、前置拒绝都零 IO、最终失败一次审计。PromptBuilder 对未知声明
报错而不是删减列表；该错误发生在模型调用前，没有实际 tool call，不伪造工具审计。

| 情形 | 规则 |
|---|---|
| 成功 | 立即结束，retryable=false |
| 返回失败且 retryable=true | 依次等待100/200/400ms，最多再调用3次 |
| 返回失败且 retryable=false | 不重试 |
| 未分类异常 / Sandbox 拒绝 / 非法参数 | 转不可重试失败；不猜测异常瞬态性 |
| 中断或取消 | 保留中断标志、终止退避/执行、最终失败，不继续重试 |
| 审计异常 | 记录可观测失败，不进入工具重试，不改变已发生的外部副作用 |

耗尽重试后返回最终失败，不能再额外调用一次。最终结果的 retryable 表达失败类别，不是要求
ReAct 自动再试；ReAct 仍只按模型输出循环，外层 max_iterations 默认10且由 Profile 覆盖。

审计端口每个逻辑调用恰好调用一次，success 取最终结果而不是“没有抛异常”；latency 含退避。
保留既有端口字段和 SQLite schema。测试需分别覆盖返回失败、抛错、重试后成功、耗尽、取消、
审计失败不重放、未授权调用零 IO；既有成功/抛错测试补充明确 Profile fixture，不削弱原断言。
