# Data Model: 统一 Tool 体系

本节不新增数据库表、Profile 字段或第二套 Agent 模型。以下为计划中的内存契约，非已实现声明。

## 1. OryxTool 与注册表

| 对象 / 字段 | 类型 | 约束 |
|---|---|---|
| OryxTool.name | String | 非空白、全局唯一；等于 Profile.tools 和模型调用中的名字 |
| description | String | 非空白，不嵌入解析后的凭证 |
| inputSchema | String | JSON 对象 Schema；可本地编译，不访问外部引用 |
| execute 输入 | String | JSON 对象文本，先验证再绑定，不能把输入当 Java/Shell 代码执行 |
| ToolRegistry 索引 | 有序 Map<String,OryxTool> | 不覆盖重名；MCP 单服务批量注册原子化 |
| toolTable | 不可变 Map 快照 | registry 完成启动注册后发布给两个 core 消费者 |

注册生命周期：构建 → 注解注册 → 允许的 MCP 服务注册 → 冻结/发布。
冻结后拒绝新增；重启重建，不提供热注册产品契约。Profile 过滤不改变全局索引。

## 2. ToolResult

| 字段 | 类型 | 语义 |
|---|---|---|
| toolName | String | 既有字段，必须对应当前调用名 |
| success | boolean | 当前尝试是否成功 |
| content | String | 成功输出，可为空字符串；失败通常为空 |
| errorMessage | String | 失败的明确、安全诊断，成功时为 null |
| retryable | boolean | 本次失败是否为可安全重放的瞬态失败；成功恒 false |

四参构造、ok(name,content)、fail(name,error) 保留，缺省 retryable=false；增加五参规范构造和
fail(name,error,retryable)。不增加“重试次数”字段到 Profile 或持久化 schema。
执行器防御 null 结果、错配 toolName 和矛盾成功/失败字段，按不可重试契约错误审计。

逻辑调用状态：前置校验 → 第一次尝试 → 成功结束 / 不可重试失败结束 / 瞬态失败退避后重试。
最多进入四次工具执行；中断终止；最终审计一次。ReAct 下一轮是新的模型决策，不与内部重试共用计数。

## 3. Sandbox 前向值对象

- Sandbox：enforce(SandboxAction)；拒绝时抛 SandboxViolationException。
- SandboxAction：type: ActionType、target: String；均必填，target 非空白。
- ActionType：仅 FILE_ACCESS / SHELL_EXEC / HTTP_REQUEST。
- PermissiveSandbox：仅测试类，不打包到应用、不自动装配。

文件 target 为规范化绝对路径；Shell target 为原始命令；HTTP/notify target 为已解析的请求 URL；
MCP target 为配置中的可执行文件。记录日志/错误时不能回显含凭证的 target。
真实路径/命令/域名白名单归 DR-002；本节默认拒绝不等于白名单实现。

## 4. MCP 配置与连接

| 字段 | 类型 | 约束 |
|---|---|---|
| servers | List | 沿用 init 顶层；空列表有效 |
| name | String | 非空白且配置内唯一 |
| transport | String | 仅 stdio |
| command | List<String> | 非空；首项可执行文件，其余是独立 argv，不再经 shell 解析 |
| env | Map<String,String> | 可缺省；键不改名，值中的环境占位符必须解析成功 |

McpServerConfig 是 tool 模块包私有载体，不向 core 暴露 SDK 配置类型。解析后的凭证只随目标连接
持有，不注入 ToolResult、Profile、Schema 或审计参数；清理诊断中的已知配置秘密。

连接状态：待连接 → 安全检查 → 30秒发现窗口（initialize → 有界逐页listTools/全部校验）
→ 调用线程确认按时完成且未取消 → 原子注册 → 可用 → 关闭。
任一步失败：脱敏 WARN → 关闭部分资源 → 跳过此服务；其他服务继续。默认安全拒绝也遵循此路径。
启动失败没有 Session 身份，只写可观测启动日志，不伪造一条 tool_invocations。
每服务发现状态仅包含已见游标、页计数（最多32）、工具计数（累计最多1000）、截止时间和取消状态。
这些是可丢弃运行资源，不落新表；工作线程不写Registry，清理等待独立最多10秒。

McpToolAdapter 内部关联 serverName、同步 client、远端 tool 描述、启动动作。远端工具名不自动
加命名空间；冲突拒绝而非重命名，保持 Profile 引用和执行转发一致。
额外持有原始 inputSchema 的不可变完整JSON文本，getInputSchema与参数校验共用它，不从SDK
有限JsonSchema record反推。包私有SchemaPreservingMcpJsonMapper按返回页对象身份绑定原始Schema，
最多32个待消费页，消费即删除，取消/失败/冻结清理；不是按名称长期保存的第二套工具索引。

## 5. Profile 与 NotifyTarget（复用）

Profile.tools 是精确工具名集合，空列表代表不给工具；重复/未知声明明确报错，不静默忽略。
Profile.mcpServers 是 MCP adapter 执行时的服务引用限制，不能自动扩大 tools。

Profile.notifyChannels 的每项仍是 type + 渠道配置。NotifyTools 从静态 ProfileContext 取当前值，
转换为已有 NotifyTarget(channelType, Map<String,String>)；不新增 name/id/url 顶层模型字段。
缺省取首项；显式 channel 按 type 唯一匹配，多个同 type 时必须明确拒绝歧义。

## 6. 审计（复用）

现有 ToolInvocationAudit.record 签名、JpaToolInvocationAudit、tool_invocations 列不改。
一次逻辑调用写一次最终状态：success=true 对应 completed，其他对应 failed；超时在 error 中表达，
本节不为写 timeout 状态改动既有 boolean 审计端口。latency 包含所有尝试与退避。

原始调用参数仍按既有参数 JSON 契约落库；不能把 MCP env 或 notify 的配置 URL 额外注入参数。
审计持久化故障可观测但不触发工具重试；不以补写审计为由重复外部操作。

## 7. 延期引用

DR-001：Memory 工具及默认 Profile 回归（22）。DR-002：生产白名单与课件动作枚举同步（24）。
DR-003：edit_file/grep/glob/ask_user/web_search 后续必须补齐，先同步事实源范围。
