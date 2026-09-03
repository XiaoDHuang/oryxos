# oryx-memory-v1：受控 Mem0 适配协议

日期：2026-08-30。此为 **OryxOS 自有外部适配协议设计**，不是 Mem0 官方已有 API；当前尚未实现。组件位于计划中的 integrations/mem0-adapter，不占用 OryxOS `/api/v1` 的十个业务端点。

## 1. 通用规则

- HTTPS，JSON UTF-8；前缀 `/oryx-memory/v1`。所有请求必须认证，包括 capabilities；不暴露原生 /configure、/reset、任意删除和历史查询入口。
- `Authorization: Bearer ...`；API key由安全随机源生成32字节，编码为规范无padding的base64url token（43个ASCII字符）。摘要为token原始ASCII字节的SHA-256小写hex，不包含Bearer前缀、不先解码或trim。启动只加载§7的摘要/工作区绑定，比较摘要使用恒定时间比较；缺绑定失败，不提供默认key。每个路径workspace必须与凭证一致。格式校验不能证明随机性，随机生成由Secret管理流程保证。
- Java 生成 operation_id UUID；重用 ID 必须有相同请求hash。算法为SHA-256，输出小写hex：UTF-8编码的protocol、标准小写workspace UUID、kind、scope四个字段依次以NUL分隔，末尾再接NUL和精确正文（SAVE content或RECALL query）。前四字段只允许规定字面量，正文不作Unicode规范化；拒绝非法Unicode，不采用平台默认编码。
- content/query最大32KiB UTF-8，非空白且不得含U+0000；JSON请求体最大256KiB，拒绝未知字段/枚举。NUL拒绝只作用于Mem0远端正文/查询，不能删除、替换或编码改写后继续处理；字段间请求hash的NUL分隔符保持不变，不改变006/SQLite能力。每个HTTP响应（包含JSON信封、转义、items及receipt）最大1MiB，按实际序列化后的UTF-8字节计数，不能用原文长度或条目数推算；细则见§3.1。
- 响应均带服务生成的规范UUID request_id（固定36个ASCII字符，不直接透传来访header或上游ID）；operation响应带operation_id/workspace_id，状态和错误可判定。错误只用固定中文模板，不包含凭证、远端原始响应或栈；request_id等信封字段均计入响应预算。
- SDK 固定 mem0ai 1.0.11 对应源码；server operation 处理期限 30 秒，SQL 语句/锁等待及模型请求均受剩余期限约束。同步处理，无业务后台推理队列。

## 2. 端点

| 方法 / 路径 | 输入 | 输出 |
|---|---|---|
| GET /capabilities | 认证 | 协议、构建/SDK版本、schema版本、能力及固定限制；不含任何 Secret |
| PUT /workspaces/{w}/operations/{id} | SAVE 或 RECALL 判别体 | 200 COMMITTED；202 RECEIVED/RUNNING；或固定失败 |
| GET /workspaces/{w}/operations/{id} | 认证 | 同一操作的持久状态/结果；不存在时返回404，不能据此推断已回滚 |
| POST /workspaces/{w}/snapshots | 空 JSON 对象，不接收scope | 当前revision的scope无关snapshot_id、到期时间、CORE与窗口归档总数 |
| GET /workspaces/{w}/snapshots/{s}/entries | scope、可选cursor、page_size≤100 | 固定快照页、total_count、complete、next_cursor |

以上路径均相对于前缀。路径 ID 和 cursor 由客户端编码，客户端不接受服务返回的任意 next URL。

## 3. 操作体与结果

SAVE 体：`{"kind":"SAVE","scope":"ARCHIVAL","content":"项目已经升级到 Java 21"}`。

CORE 同样使用 SAVE 但只原文追加，不执行 LLM/embedding。ARCHIVAL 必须启用提炼/合并/替换；调用者不得通过 infer=false、metadata 或 memory_type 绕过保护。

RECALL体：`{"kind":"RECALL","query":"项目的 Java 版本"}`。只查当前ARCHIVAL，最多20条，按精确余弦相似度DESC、同分memory_id ASC；score≥0。只读快照，不搜索raw_input/旧版本；无匹配是正常空数组。结果按§3.1的字节预算返回完整条目的排序前缀，不能截断正文。

COMMITTED receipt 必须含：

- operation_id、workspace_id、kind、scope、state、request_hash、committed_at、revision；RECALL的scope固定ARCHIVAL。
- SAVE：outcome=CHANGED/NOOP，action_counts（ADD/UPDATE/DELETE）、affected_ids、history_complete=true。不能把 SDK 返回200当成此确认。
- RECALL：items（memory_id、content、scope=ARCHIVAL、version_id、score）、snapshot_revision、returned_count、truncated_by_bytes；无history正文。
- 重复 ID 返回相同终态数据，并标 replayed=true；不能再次调用模型。相同 ID 不同请求返回 409 REQUEST_ID_CONFLICT。

facts=[] 的合法提炼或明确 NONE 可为 NOOP：保留输入与审计，成功含义是“已处理且状态不需更新”，不保证新增条目。坏 JSON、缺字段、吞错及未解释空动作不能为 NOOP。

RECEIVED/RUNNING凭据同样包含operation_id/workspace_id/kind/scope/request_hash，另带deadline_at。FAILED/ABORTED的持久终态凭据包含上述身份字段、completed_at、固定error_code和memory_effects_applied=false；false仅说明记忆投影未提交，不否认已经登记的原始输入、审计或模型调用。单独HTTP错误码或错误文字不是这种终态凭据。

### 3.1 生成结果与序列化预算

- 内部LLM/embedding单次请求及响应各最多1MiB实际HTTP实体字节，在发送/解析前检查；超限不得无限读取或交SDK宽松解析。
- 单次提炼最多64个facts，单次动作决策最多128项（含NONE）；每条待保存fact/new_content须为合法Unicode、不含U+0000、非空白且≤32KiB UTF-8。全部暂存变更也受128项上限约束；超限或NUL立即置fatal，保留输入/失败审计，但不提交current/versions。
- 写事务提交前必须验证生成条目、暂存动作及完整成功receipt的序列化预算。用与HTTP发送相同的序列化规则，计入所有固定字段以及replayed取值的两种包装；不得在COMMITTED后才发现响应超限。超限产生422 ENGINE_LIMIT_EXCEEDED和匹配的FAILED凭据，不能转成NOOP或静默舍弃SAVE动作。
- RECALL先取排序后的最多20个候选，再选择能放入1MiB完整receipt的最长完整前缀，returned_count等于items长度；因字节不足少于候选数时truncated_by_bytes=true，否则false。至少一个候选存在但连一个完整项也容不下时，明确返回RESULT_LIMIT_EXCEEDED，不冒充无匹配；无匹配才返回空items且flag=false。
- 快照分页按完整页信封预算分页，不删条目或改total_count；单项无法放入时明确失败，不产生空的非终页。对已有不合规数据也不得截字绕过。Java仍独立校验1MiB硬上限、计数与标记。
- 边界用例包含双引号、反斜杠、Unicode转义和多字节文本；20条各32KiB的合法原文不代表JSON响应必然≤1MiB。

## 4. 原子提交与恢复

实现基础（T032）：共享`contracts.py`使用冻结的Request/OperationIdentity及分状态receipt类型，不把StagedResult转换为成功凭据。持久终态JSON不含逐次HTTP的request_id/replayed；发送时在同层补入这两个字段，使用排序键、无额外空白、ensure_ascii=false、禁止NaN的UTF-8序列化。路由后续负责生成新的request_id，不能透传来访ID。提交前以同一序列化器检查replayed=false/true两种完整包装；此DTO/预算能力本身不证明数据库已经提交。

操作raw_input与不可延长的30秒deadline先独立登记；RECEIVED即有期限，重复ID不刷新期限，只有未过期记录可取得owner。模型只作用暂存，所有模型请求通过受控wrapper持久化STARTED/终态审计。SDK异常即便被内部捕获，也必须通过fatal latch阻止提交。

最终写事务必须同时完成versions/current/receipt，校验baseline revision、owner_token、RUNNING状态和期限。冲突返回WRITE_CONFLICT，不自动重推理。合法NONE的metadata-only update先暂存、归一化为NOOP证据，不刷新current内容/recency；不要求SDK为NONE产生它原本没有的history/返回事件。越scope、正文变化或绕过暂存写入均fatal。

出现历史或审计失败时不得提交业务变化。COMMIT 应答丢失时查询主库状态；查不到确定终态可返回 202/503 OUTCOME_UNKNOWN，不能伪造 FAILED。超期存活操作恢复为 ABORTED 只修改状态，不再次运行 Mem0；晚到提交被 fence 拒绝。

**SAVE确认状态机优先于HTTP错误分类**：本地校验/guard拒绝且请求尚未交给传输执行时为NOT_DISPATCHED，可确定失败；在首次交给HTTP传输执行前即设DISPATCHED，不依赖“是否收到响应”判断是否派发。此后只有身份字段和request_hash全部匹配的可信持久终态才可确认结果：COMMITTED还须history_complete=true；FAILED/ABORTED还须memory_effects_applied=false。结果冲突、字段缺失或相互矛盾都不构成确认。

DISPATCHED后若没有该凭据，包括断链、读超时、5xx、HTTP 200畸形/缺receipt、错误hash/ID/workspace/scope、history_complete=false等，均以原operation_id查询状态；不能直接归类为“确定未保存”的协议/服务失败。HTTP 4xx如仅有错误信封、没有匹配持久终态，也不证明该逻辑操作未提交。确认前收到的HTTP分类只作脱敏诊断。

客户端在配置的总期限内（默认40秒）最频每250ms查询一次状态，只GET、不再次PUT或换ID重推理。GET的RUNNING/RECEIVED/404、无效凭据、鉴权拒绝或无法连接仍未确认；期限耗尽、中断或因权限等原因已无法继续确认时返回MEMORY_OUTCOME_UNKNOWN及原ID，可以提前结束而不改判确定失败。后续人工可查询状态，不新增Agent恢复工具。RECALL/load不改变记忆投影，按只读请求规则报告超时/协议/访问失败，不声称撤销已产生的审计或模型调用。

## 5. 快照页

snapshot_id的签名payload为v=1、type=snapshot、workspace_id、revision、nonce(UUID)、issued_at、expires_at（UTC整秒，期限300秒）。**snapshot_id不绑定scope或排序**，同一个ID用于CORE和ARCHIVAL；创建时不接收scope或任意历史revision。CORE total_count为全量；ARCHIVAL total_count为该快照最近100条窗口数。

cursor是另一种payload：v=1、type=cursor、snapshot_digest（完整snapshot_id ASCII字节的SHA-256）、workspace_id、revision、scope、sort_id、after（最后完整条目的排序键）、expires_at。它绑定scope/排序及具体snapshot，期限不得晚于快照。core的sort_id为core_created_asc，after=(created_revision,memory_id)；归档为archival_updated_asc，after=(revision,action_index,memory_id)。

两类token均用ADAPTER_CURSOR_SECRET对UTF-8 JSON payload做HMAC-SHA-256；签发时键名排序、无额外空白、字符串不作Unicode规范化，编码为无padding base64url(payload).base64url(mac)。验签针对原payload字节，随后严格校验类型/字段/绑定/期限，不互换snapshot/cursor；Java视为opaque值。CORE cursor用于ARCHIVAL、不同snapshot的cursor或错误sort_id均400 INVALID_INPUT，工作区越权403，到期410；无cursor时开始对应scope第一页。

每页字段：snapshot_id、revision、scope、items（memory_id/version_id/content/创建和更新revision）、total_count、complete、next_cursor。分页排序固定：核心按创建revision/id；窗口归档按版本revision/action_index/id升序。cursor 为不透明签名值，不能用 offset 在移动数据上拼页。

complete=true时next_cursor必须为null；否则必须非空页且cursor前进，客户端拒绝重复cursor。累计计数、ID唯一性、scope及revision完全一致才接受load；过期、跨工作区、缺页、重复页或非法签名均失败，不注入部分核心。成功保存后新快照至少包含该提交revision；读取主库，不用延迟副本。

## 6. 错误映射

| HTTP / 分类 | 持久结果 | Java 行为 |
|---|---|---|
| 400/413 INVALID_INPUT | 无处理，或记录确定失败 | SAVE派发后按§4确认；只读/派发前可直接失败 |
| 401/403 ACCESS_DENIED | 不泄露工作区存在性、不处理当前未授权请求 | SAVE派发后缺匹配终态仍未确认；只读/派发前拒绝 |
| 404 OPERATION_NOT_FOUND | 仅表示当前查不到该 ID | 若 PUT 已发送，仍为可能未知，不重放 |
| 409 REQUEST_ID_CONFLICT | 不改原操作 | 不接受不同hash的旧成功；SAVE无匹配终态按§4处理 |
| 409 WRITE_CONFLICT | FAILED，无业务提交 | 匹配FAILED凭据才可确认冲突；不重推理 |
| 410 SNAPSHOT_EXPIRED | 读取失败 | load整体失败，不使用部分页 |
| 422 ENGINE_INVALID_RESULT/ENGINE_LIMIT_EXCEEDED/RESULT_LIMIT_EXCEEDED | FAILED，无业务提交 | 匹配FAILED凭据时明确失败，否则SAVE按§4处理 |
| 503 HISTORY_UNAVAILABLE/AUDIT_UNAVAILABLE | 禁止业务提交；若提交应答未知则另用OUTCOME_UNKNOWN | 明确失败或未知，不降级 |
| 504 OPERATION_DEADLINE | 已确定FAILED/ABORTED才称超时失败；否则OUTCOME_UNKNOWN | 区别超时与可能已提交 |

客户端对无法校验的200、畸形JSON、缺receipt和跨scope结果不得报告成功；SAVE按§4先查询终态，仍未确认则OUTCOME_UNKNOWN，只读请求可直接协议失败。服务错误分类不能由上游message字段直接决定。

## 7. 部署契约

2026-08-31用户批准的安全回移覆盖下述SDK原始基线：实际安装为`mem0ai 1.0.11+oryx.1`，仅回移官方FAISS补丁，基线Git及原算法不变；capabilities中的SDK版本须如实报告受控版本。来源与扫描判据见[SDK安全回移契约](sdk-security-backport.md)，不得重新装回未修复的1.0.11或直接升级到新算法。

设计基线：Python 3.12.14；mem0ai 1.0.11；FastAPI 0.141.1、uvicorn 0.52.4、psycopg 3.3.4；PostgreSQL 17.11 + pgvector 0.8.6。Mem0源码依赖固定到Git SHA 144627c4ce5bc4db6acac17cbd158065f2b27a8d，不仅依赖版本标签；构建记录实际文件摘要。版本已从官方源核验；兼容解析、全传递锁和镜像digest尚需在实现时产出，未通过测试/漏洞扫描不得部署。

环境/Secret 输入：ADAPTER_DATABASE_URL、ADAPTER_CLIENT_BINDINGS（workspace/key摘要绑定）、ADAPTER_CURSOR_SECRET、ADAPTER_LLM_BASE_URL/MODEL/API_KEY、ADAPTER_EMBEDDING_BASE_URL/MODEL/API_KEY/DIMENSIONS、ADAPTER_ALLOWED_ORIGINS、ADAPTER_TLS_CERT/KEY、MEM0_DIR。禁止缺值时回退云端；模型/embedding精确origin必须在允许清单及基础设施出口策略内。

### 7.1 绑定与允许目标的格式

ADAPTER_CLIENT_BINDINGS是**非空JSON数组**；每项只能有key_sha256与workspace_id两个字符串字段。key_sha256必须为64字符小写hex，workspace_id为规范小写带连字符、非nil UUID。任何未知/缺失字段、非字符串、畸形JSON或非法值均使整个配置失败，不跳过坏项。

key_sha256在整个数组中必须唯一：即使重复项指向同一工作区也拒绝，不能后项覆盖前项；一个key只绑定一个workspace。多个不同key可绑定同一workspace以支持显式换钥。配置只在启动加载，不增加热加载或权限管理接口。

格式示例（摘要是占位数据，不对应可用凭证；真实token只能经Secret渠道提供）：

```json
[
  {"key_sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","workspace_id":"11111111-1111-4111-8111-111111111111"}
]
```

ADAPTER_ALLOWED_ORIGINS是**非空JSON字符串数组**，例如 `["https://llm.example","https://embedding.example:8443"]`。仅允许HTTPS origin：无userinfo/query/fragment、path为空或仅/；host规范化为小写IDNA ASCII或规范IP，默认443及末尾/归一化去掉，非默认端口保留。不接受CSV、通配符、后缀匹配或路径前缀；规范化后重复项也使整个配置失败。LLM/embedding base URL同样须为HTTPS且无userinfo/query/fragment，可以有固定API路径；其规范origin必须精确在列表中，每次请求仍校验，不继承代理/云回退。DB及日志出口另受部署网络门禁约束。

ADAPTER_CURSOR_SECRET也是安全随机32字节的规范无padding base64url；HMAC使用解码后的32字节，不能复用API token或默认值。启动时若SHA-256(cursor Secret的ASCII编码)等于任一key_sha256则拒绝。API token摘要使用ASCII token而非解码字节，二者不得混淆。schema校验异常仅报固定分类/字段名，不回显环境原文或Secret；R3仍须验证实际出口。

MEM0_TELEMETRY=false 必须在 import 前生效；禁 graph/reranker/vision/默认云路由，忽略隐式 proxy/OpenRouter 环境继承。固定 MEM0_DIR（有非业务config文件），SDK内容日志不能进入外发日志系统。标准TLS验证、显式模型身份、按剩余deadline调用；密钥不进参数正文/审计JSON/异常。

capabilities 要求 protocol=oryx-memory-v1、staged_engine/atomic_history/revision_pagination=true、固定限额与schema版本；这是兼容性检查，不替代真实故障注入和出站/审计证据。

### 7.2 启动校验的实现边界（T031–T032）

- Settings仅接受上述显式必填字符串，不trim Secret或默补地址；只解析配置，不连接数据库/模型。模型身份与请求包装复用同一个Endpoint规则。embedding维度为规范十进制正整数，当前基础类型上限为有符号32位；实际vector(D)上限、已有schema维度及模型相容性仍须T036验证，不能仅凭正整数放行部署。
- DATABASE_URL为显式单目标postgresql/postgres URI，必须含用户名、密码、host和数据库名；不接收libpq键值串、service或多主机隐式路由。query只接受sslmode、sslrootcert、sslcert、sslkey、connect_timeout、application_name，重复/未知参数、authority覆盖和空值拒绝。此处是无连接的结构检查，不证明DB出口/TLS/权限合规；实际连接的环境继承控制和部署检查由后续持久层/R3完成。
- TLS_CERT/KEY为存在的绝对普通文件路径，启动用标准SSL加载并校验证书/私钥配对，最低TLS1.2；加密私钥缺解锁渠道时固定失败，不交互询问密码。不得将公开单测证书/私钥用于部署，它们不进入运行镜像。
- MEM0_DIR为显式、已存在、可写的绝对目录，不自动新建或退回用户主目录。SDK导入前拒绝config.json链接/非普通文件，关闭遥测并固定解析后的目录；已缓存导入的SDK拒绝重新配置，失败后须以干净进程重新启动。目录ACL和实际文件系统隔离仍属于部署门禁，应用检查不是防竞态的OS沙箱。
- 本阶段只返回配置、TLS上下文和经过源校验的暂存类型，不构造原生Memory、数据库连接、FastAPI路由或模型客户端，不声明capabilities已具备真实事务能力。
