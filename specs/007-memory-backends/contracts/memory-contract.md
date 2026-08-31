# Java Memory 与配置契约

日期：2026-08-30。设计契约，不表示代码已实现。外部协议见 [mem0-adapter-api.md](mem0-adapter-api.md)。

## 1. 保留入口

core 中保持 `MemoryService` 的 `List<Message> buildContext(Session,int)`、`void remember(String,MemoryScope)`、`List<String> recall(String)`；MemoryScope 仍为 CORE/ARCHIVAL。只更新查询 Javadoc，不改签名或引擎依赖方向。

MemoryTools 保持 `String saveMemory(String content,String scope)` 与 `String recallMemory(String keyword)`，Tool 名仍为 save_memory/recall_memory，scope 缺省 archival，已有大小写兼容规则不变。Markdown 成功文本仍为“已记住”，无匹配仍为“没有找到相关记忆”；Mem0 返回前须完成协议确认，不能用错误字符串伪装成功。

Mem0完成可能是CHANGED或合法NOOP，统一成功文案用“记忆处理完成”，不暗示必然新增事实。由MemoryConfiguration选择包内回复策略；既有public MemoryTools(MemoryService)构造入口仍保留原文案。异常必须抛出并映射失败，不能被回复策略吞掉。

## 2. 新增及改动类型清单

| 类型 / 落位 | 契约 |
|---|---|
| LongTermMemoryStore / memory | `void append(String,MemoryScope)`、`String load()`、`List<String> recall(String)`；load 为完整核心+后端归档窗口，无空段 |
| MarkdownMemoryStore / memory | 包装 LongTermMemory，复用 006 解析、锁、原子写、4000 Java char 窗口 |
| SqliteMemoryStore / memory | 事务提交后成功，四字段原文保存，核心全量、归档 100 条、字面查询 |
| Mem0MemoryStore / memory | 只调用自有 oryx-memory-v1 适配协议，不直连原版 Mem0 REST |
| MemoryOutboundGuard / memory | `void check(URI target)`，不通过时抛 SecurityException；不得有默认 no-op |
| MemoryOperationException 及其 Code / memory | final 受限异常，仅固定分类+可选 UUID；OUTCOME_UNKNOWN 必须有 operationId；不接收任意 message/URL/响应正文 |
| MemoryEntry / storage | 四字段实体，见 data-model；MemoryEntryRepository 及 UtcMillisInstantConverter 为持久层类型 |
| HttpWhitelistSandbox / tool | implements Sandbox；HTTP_REQUEST 严格白名单，其余 ActionType 拒绝 |

全局 MemoryProperties 只绑定/校验 backend；独立 Mem0Properties 仅在选中 mem0 的条件配置内绑定和校验。配置类型只属于 memory；boot组合配置和传输/DTO尽量包内，不新增core安全端口。不得通过全局嵌套绑定提前解析未选Mem0的必填占位符。新增公共范围以此表为准。

`MemoryServiceImpl(LongTermMemory)` 构造入口保留，委托 Markdown Store；新增接受 LongTermMemoryStore 的构造入口。Spring 只注入唯一 Store。已有自定义 MemoryService/LongTermMemory 的 backoff 保留：自定义实现不触发未选后端访问，非法 backend 值仍必须校验失败。

## 3. Java 配置

`application.yaml` 支持以下键；样例值都不是实际凭证：

| 键 | 默认 / 校验 |
|---|---|
| memory.backend | markdown；仅 markdown/sqlite/mem0，重启生效 |
| memory.mem0.base-url | 选中 mem0 必填；`${MEM0_BASE_URL}`；HTTPS、无 userinfo/query/fragment，固定自托管 origin |
| memory.mem0.api-key | 选中mem0必填；`${MEM0_API_KEY}`；32随机字节编码的规范43字符无padding base64url token，不打印、不硬编码 |
| memory.mem0.workspace-id | 选中mem0必填；`${MEM0_WORKSPACE_ID}`；规范小写带连字符、非nil UUID，与服务凭证绑定一致 |
| memory.mem0.connect-timeout | 3s，正值，不得超过 operation-timeout |
| memory.mem0.read-timeout | 30s，正值，不得超过 operation-timeout |
| memory.mem0.operation-timeout | 40s，范围 5s–55s；覆盖单个 append/recall/load 的全部请求和分页 |
| http.allowed_domains | 复用既有白名单；域名/IP 精确项，不从 base-url 自动添加许可 |

未选 mem0 时不解析其必需 Secret、不创建远端客户端/guard探活。选择合法且配置齐全后，启动才调用 capabilities 做兼容性检查；真实启用还需部署证据，不把服务自报 flags 当安全审计。

工作区仍按现有 `.oryxos` 和显式构造入口管理，不新增 Profile 字段。独立工作区须用不同 UUID/凭证绑定；复制或移动同一逻辑工作区需保留其身份，不能把路径 hash 当身份。

## 4. 白名单与调用边界

- HttpWhitelistSandbox 作为缺少自定义 Sandbox 时的默认实现；读取既有 http.allowed_domains，空列表拒绝。这个提前实现会覆盖既有 HttpTools/Notify 的 HTTP 校验；File/Shell/MCP 启动仍拒绝，除非用户已有显式 Sandbox 实现。
- 使用 URI 解析和规范化 host（大小写、IDN规则统一）；精确匹配，不做 contains、尾缀猜测或任意正则。拒绝缺 host、userinfo、非法 URI；allowed_domains 不代表任意协议许可。
- Mem0 更严格：只向配置 origin 的自有固定路径发 HTTPS；每次最终请求 URI 均调 MemoryOutboundGuard。boot 将其委托到 Sandbox HTTP_REQUEST；将 Sandbox 拒绝转换成安全异常，不让 memory 导入 tool 类型。
- 缺 guard 且选中 Mem0 时启动失败；禁止重定向、隐式代理/默认云地址和 SDK 直连。内网 DNS/IP 出口由部署策略进一步限制；域名白名单不是全链路数据不出域证明。
- 传输边界捕获 guard 的 SecurityException，并在任何I/O前转换成 MemoryOperationException 的 MEMORY_ACCESS_DENIED；有逻辑操作UUID则携带，没有则为空。不透传原异常正文，不让memory导入tool类型，不能落回普通“工具方法执行失败”。
- 传输最大响应 1MiB/页，超限失败，核心分页不完整则 load 整体失败。TLS 使用正常证书验证；测试用专属可信 CA，不加生产 trust-all 开关。
- snapshot_id只绑定工作区/revision/期限，不绑定scope；同一个snapshot可加载CORE和ARCHIVAL。cursor绑定具体snapshot的摘要、scope和排序，不能跨scope使用。客户端分别累计两类总数与ID，不把CORE的cursor复用给ARCHIVAL。
- 1MiB按整个JSON信封序列化后的UTF-8字节计数，不能用content原文长度推算。RECALL接受服务端按字节预算返回的完整排序前缀，并验证returned_count/truncated_by_bytes；标记保留在服务receipt及脱敏诊断中，List<String>仍只含真实命中，不插入伪记忆或截断条目正文。
- 总期限不能只依赖 socket read timeout：以单次受控虚拟线程执行同步 HTTP 并由调用方限时等待，超时取消、关闭流/连接，保留中断。禁止固定线程池、CompletableFuture 编排或并行 Tool；验证慢速响应及资源回收，不让 trickle response 绕过期限。

## 5. 确认、错误和审计

Mem0 append/recall为每个逻辑调用生成UUID，在PUT前固定，不由正文hash推导；两次有意相同保存是两个操作。成功必须取得匹配operation_id/workspace_id/kind/scope/request_hash的COMMITTED凭据；append另检查history_complete=true，recall检查items、快照和预算标记。成功后的读取取主库不早于该提交的快照。

append在交给HTTP传输执行前从NOT_DISPATCHED切换为DISPATCHED，PUT只执行一次。此后只有匹配的持久终态才能确定结果：COMMITTED且history_complete=true为成功；FAILED/ABORTED且memory_effects_applied=false才能确定未提交。HTTP状态本身、错误文字或不同hash操作的旧成功不是依据。

已派发SAVE的断链、超时、5xx、畸形/超限200、缺receipt、错误ID/hash/workspace/scope、history_complete=false等均先按原ID查询，不直接报告“确定未保存”的PROTOCOL_ERROR/SERVICE_FAILURE。GET只作为原SAVE的确认步骤：RUNNING/RECEIVED/404、鉴权/guard拒绝、无效响应和无法访问都仍未确认，不能用读取异常覆盖未知保存状态。剩余总期限内最频250ms一次GET；耗尽、中断或权限拒绝等已无法继续确认时，以MEMORY_OUTCOME_UNKNOWN+原ID结束，不重PUT、不换ID、不重推理。

NOT_DISPATCHED的本地校验/guard拒绝或确定未发出的超时可直接使用对应分类。独立RECALL/load不修改记忆投影，可以直接报告读取超时/协议/访问失败且不重放PUT；不宣称撤销已产生的服务审计或模型调用。SAVE确认状态机优先于HTTP错误映射。

固定错误分类：MEMORY_INVALID_CONFIG、MEMORY_ACCESS_DENIED、MEMORY_PROTOCOL_ERROR、MEMORY_SERVICE_FAILURE、MEMORY_TIMEOUT、MEMORY_OUTCOME_UNKNOWN、MEMORY_WRITE_CONFLICT、MEMORY_HISTORY_FAILURE。消息由异常类固定生成；未知提交示例为“记忆保存结果不确定，请勿重复保存；operationId=UUID”。

错误链：Mem0MemoryStore 抛受限异常 → MemoryService/MemoryTools 原样抛出 → AnnotatedToolAdapter 只对该精确 final 类型映射 `ToolResult.fail(name,safeMessage,false)`。其余异常继续原有脱敏，不透传 getMessage。不要在 String 方法内 catch 后返回错误文本。

ToolExecutor 保留已经校验名称、明确失败且不可重试的结果，即便线程随后中断；保留中断标志，不清除它以继续工作。仍不能把中断当作外部副作用已撤销。该兼容性变更需独立回归现有停止/重试用例。

既有 ToolInvocationAudit 不增字段/签名：失败行仍为 status=failed，error/error_message 中用固定分类和 operationId 区分超时/未知，不能宣称自动写入 status=timeout。服务内部审计通过 workspace+operation_id 关联原始输入、内部调用和结果；与 Java tool_invocations 分开验收，不冒充已建立跨库外键。

## 6. 明确验收

默认兼容、非法选择、禁用后端无行访问/无网络、完全未设置MEM0环境变量也能启动本地后端、构造入口backoff、真实SQLite提交与时间转换；HTTPS/白名单/缺guard/跨origin/慢响应。首次派发前guard拒绝须ACCESS_DENIED且无I/O；已派发SAVE的状态查询被guard拒绝则UNKNOWN，不覆盖原保存状态。还须验证未知保存不重放/编号可追溯、中断不丢失败明细及三后端差异；006断言全部保留。详见 [quickstart](../quickstart.md)。
