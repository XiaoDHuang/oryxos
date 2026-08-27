# Contract: MCP stdio 配置与执行

## 配置格式

沿用 .oryxos/mcp_servers.yaml 与 InitCommand 已有格式，不新建配置路径或 args 字段。

```yaml
servers:
  - name: local-business
    transport: stdio
    command: ["java", "-jar", "/opt/oryxos/tools/business-mcp.jar"]
    env:
      BUSINESS_API_KEY: "${BUSINESS_API_KEY}"
```

示例仅说明格式，不在 plan 阶段安装、启动或向该服务发送数据。生产默认拒绝启动直至 DR-002。
空配置用 servers: []；文件缺失视为无服务，格式错误给脱敏 WARN，不拖垮 OryxOS。

- name 非空且唯一；重复配置名均拒绝，其他合法服务仍处理。
- transport 只允许 stdio，SSE/HTTP 不自动降级。
- command 为非空字符串列表，首项可执行文件、后续 argv 原样传入，不执行 shell 字符串。
- env 可缺省；仅解析其字符串值中的 ${ENV_VAR}，缺失或残留占位符拒绝该服务，键保留大小写。
- 不新增敏感标记配置键；键名包含 KEY/TOKEN/SECRET/PASSWORD/CREDENTIAL/AUTH（忽略大小写）的
  env 项视为秘密，用于元数据拒绝、日志与结果脱敏；MODE/LANG等普通短值不参与秘密子串扫描。
- 子进程环境为 SDK 已核实的最小系统变量 + 该服务 env，不继承其余宿主凭证；详细系统列表见 research。
- 不打印完整配置、环境值、命令行、原始 stderr/异常内容，不把凭证写入工具描述或 Schema。

Profile 同时使用已有字段：

```yaml
tools: [business_lookup]
mcp_servers: [local-business]
```

tools 决定模型可见名称，mcp_servers 必须包含该 adapter 的服务名；后者不隐式增加所有服务工具。
缺服务引用时执行前拒绝，不发送 callTool。

## 连接与注册

McpClientService.connectAll() 为公开生命周期入口；Spring 装配在生成 toolTable 前同步调用。
初始化10秒、工具请求30秒为内部常量，无新增 YAML 键；只使用 McpClient.sync / McpSyncClient。
单服务发现另有30秒总预算，包含初始化、所有分页与清单校验；失败资源清理最多另等待10秒。
启动前以 SHELL_EXEC 检查 command 首项，拒绝则跳过，不创建进程。

每个服务依次 initialize → 有界单页listTools → 验证所有描述/原始Schema/名字 → registry.registerAll。
必须使用SDK0.18.3的listTools(String cursor)，首请求传SDK FIRST_PAGE，不使用无参自动聚合。
最多32页、累计1000个工具；非空nextCursor重复则立即失败，第32页仍有后续游标或第1001个工具
出现即整批失败。最后一页使累计数量恰好1000时允许成功；null/空游标表示结束。
一个辅助虚拟线程完成该服务发现，调用线程同步等待剩余总预算；截止/中断时取消并清理，不用
CompletableFuture、响应式应用代码或固定线程池。工作线程不得注册工具；仅调用线程可提交按时
完成、未取消且完整验证的批次，迟到响应不得留下任何工具。循环仍逐服务推进，不并行工具。
先完整校验，再原子入表；空列表允许；一项非法或与已有工具重名则跳过此服务的全部工具，不覆盖
内置或先前服务。对坏服务 WARN 并关闭连接，然后继续好服务。
生产默认拒绝时 registry 可仅有内置/Java 元数据，MCP 尚未注册；这是明确的安全状态，不伪造工具。

McpClientService.close() 由应用销毁回调调用，逐一释放成功连接；initialize/list/register 失败也必须
释放已创建 transport/client。closeGracefully 失败后 close，单个关闭失败不阻断其他资源清理。
诊断按服务名（清除CR/LF）和错误类别记录，不能把配置凭证带进日志。不得自动重连风暴或后台轮询。

## McpToolAdapter

实现已有 OryxTool，不暴露 SDK 类型到 core。name/description 原样映射；inputSchema 序列化为
完整原始 JSON 文本，结构语义一致而非字节排版一致；重名拒绝，不擅自加 server_ 前缀。
metadata 不合法或携带已知配置秘密时拒绝注册，不改名/改描述伪装合格。

SDK JsonSchema record不保留根级allOf等未知字段，additionalProperties也只接受Boolean，
因此禁止从其重新序列化来生成OryxTool Schema。包私有SchemaPreservingMcpJsonMapper在
convertValue(Object,TypeRef)将原始清单转换成ListToolsResult之前保存并验证完整inputSchema。
专用Jackson mapper的首次JSON读取也须保留数值精度并拒绝重复键，不能先损失高精度数字再称为
原始Schema；使用独立mapper配置，不修改全局Provider的ObjectMapper。
SDK接收另一份兼容副本，仅保留其类型可表达的Schema字段；对象形式additionalProperties不强转
Boolean，完整值保留在原始副本。兼容投影不得发布或校验参数，SDK schema caching显式关闭。

完整Schema按返回页对象身份绑定，服务消费后立刻移除；最多32个待消费页，失败/取消/冻结时清理。
只在受控发现窗口收取清单，窗口外的自动刷新清单明确拒绝，不热注册。缺原始Schema绑定、字段
错位或原始验证失败时拒绝整台服务，不回退为SDK DTO。Adapter以完整原始Schema供Provider读取
及调用前参数验证；原始输入不修改，SDK副本不影响用户可见契约。

执行顺序：验证 JSON 对象/Schema → 当前 Profile 的工具与服务授权 → enforce(SHELL_EXEC,command[0])
→ new CallToolRequest(原工具名,解析后的参数Map) → 同步 callTool → ToolResult。
授权/安全拒绝时 RPC 次数为零。参数语义保真，不能偷偷加 serverName、Profile 或凭证字段。

结果转换：

- Boolean.TRUE.equals(isError) 为失败，即使传输成功；错误诊断脱敏，默认不可重试。
- 仅 TextContent 且无 structuredContent：保持顺序，用换行连接各 text。
- 有非文本 content 或 structuredContent：以包含这两部分的 JSON 文本保存结构；不丢块、
  不自动拉取资源URL、不把二进制内容当本地命令/文件操作。
- 空内容的成功结果返回空字符串；null/无法解析的结果明确失败。
- 传输异常/超时不自动等于可重试：当前通用 MCP 调用无法确认是否已经产生副作用，默认 false。
  readOnlyHint/idempotentHint 等只是可空远端提示，不能单独替代本地安全或重放保证。

工具执行失败由唯一 ToolExecutor 最终审计；客户端不另建审计表/重试循环。
进程许可不能约束远端工具内部网络/文件行为，不能据此声称是容器隔离或允许不受信任 MCP。

## Harness 与实施前置探针

McpClientServiceTest 用同步 SDK client 替身覆盖 initialize/list 失败、原子注册、重复名、坏配置、
关闭、循环游标、32/33页边界、1000/1001工具边界、发现总预算耗尽和迟到响应零注册；坏服务失败
后好服务仍能注册。课件 ConnectException 守点可用运行时传输异常包装 cause，
断言仍为 connectAll 不抛错、good_mcp_tool 存在、bad_mcp_tool 不存在。

McpToolAdapterTest 覆盖元数据、输入深相等、isError、多文本/非文本/structuredContent、授权拒绝、
Sandbox 先行、超时不可盲重试。不用 SDK 调用次数为零来冒充成功链路。
SchemaPreservingMcpJsonMapperTest从原始JSON入手，覆盖根级allOf/$defs、对象形式additionalProperties、高精度数字、
原始Schema完整深相等、原始约束拒绝非法参数、外部引用拒绝、跨页同名不混绑和绑定清理。
不得只手工构造SDK JsonSchema record来证明原始Schema保真；McpToolAdapterTest也必须使用保留后的
原始Schema断言getInputSchema与执行校验一致。

业务实现前先写 McpStdioIntegrationTest：仅启动测试自带 Java JSON-RPC fixture，不依赖 npx、
企业服务或真实模型。必须验证 initialize → listTools → callTool → close、UTF-8、配置 env 正确、
未声明宿主哨兵变量缺席，以及关闭后进程退出。显式 @Tag("integration")，运行报告必须非零用例。
进程/编码/依赖问题未验证前不能把 mock 通过写成 MCP 已连通。
