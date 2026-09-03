# 007 Data Model

日期：2026-08-30。状态：设计已定，未创建数据库或迁移脚本。依据 [spec](spec.md)、[plan](plan.md)。

## 1. 边界与所有权

| 状态 | 所有者 / 位置 | 本轮规则 |
|---|---|---|
| Markdown 长期记忆 | 现有 memory/MEMORY.md | 复用 006，零格式迁移 |
| SQLite 长期记忆 | oryxos-storage / 工作区 oryxos.db | 仅新增下面的四字段表 |
| Session/Tool/LLM 审计 | 既有 SQLite 三表 | 不改字段与已有审计端口 |
| Mem0 当前状态、历史和操作 | 外部 mem0-adapter 的 PostgreSQL | 独立于本地 SQLite，不是第二份 Java 缓存 |

外部组件自身定义协议和事务，不直接使用 Mem0 原生 server 的业务存储。PGVector 是现成存储能力，不自研向量算法；原生 SDK 的 vector/history 写入只作用于暂存对象。

## 2. SQLite memory_entries

| 字段 | SQL / Java | 约束 |
|---|---|---|
| id | INTEGER PRIMARY KEY AUTOINCREMENT / Long | 插入后生成，不能复用或改写 |
| scope | VARCHAR(16) NOT NULL / MemoryScope | CORE 或 ARCHIVAL，STRING 枚举 |
| content | TEXT NOT NULL / String | 非空白；保存原文，不 trim/拆行 |
| created_at | TIMESTAMP NOT NULL / Instant | UTC 毫秒精度、字段专用转换 |

索引 `idx_memory_scope(scope)`。不增加 workspace/profile/user 列：不同工作区必须使用不同数据库文件；共享 Session/审计数据源不代表共享多个工作区。

`UtcMillisInstantConverter` 仅用于 MemoryEntry.createdAt，`autoApply=false`。Java 截到毫秒，输出固定 `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`（使用 appendInstant(3)）；SQLite 实际保存 TEXT。读取要求合法且能规范回转，非法数据失败，不换成当前时间；不影响旧表的时间字段。

- 核心：按 created_at ASC、id ASC 全量读取。
- 归档窗口：按 created_at DESC、id DESC 取 100 条，再反转输出；不删旧行。
- 归档查询：参数化 `scope='ARCHIVAL' AND instr(content,:keyword)>0`，按时间/id ASC；大小写与特殊字符均按字面。
- schema.sql 追加幂等 DDL，不重建旧三表。未选 SQLite Memory 时不查询/写入其数据行；共享库的 schema 元数据初始化不算 Memory 数据访问。
- 选中后在 SQL 初始化完成后校验结构，不能靠 IF NOT EXISTS 掩盖同名错误表。保存提交成功后才返回；不以 JPA 一级缓存证明持久化。

## 3. 外部适配的持久模型

全部表属于外部 PostgreSQL schema `oryx_memory`。迁移为显式、版本化脚本；新增/升级先核验版本，不在应用启动时随意修改未知结构。PostgreSQL 17.11 + pgvector 0.8.6 为设计基线；实际镜像摘要与依赖锁在实现阶段生成并扫描后才能部署。

### memory_namespaces

| 字段 | 类型 | 含义 |
|---|---|---|
| workspace_id | UUID PK | 稳定工作区身份，配置提供，不从 Profile/用户派生 |
| revision | BIGINT NOT NULL | 从 0 开始；每次 SAVE 提交时事务内递增，不用非事务 sequence 作提交号 |
| created_at | TIMESTAMPTZ NOT NULL | 首次授权访问时创建 |

API key 绑定可访问 workspace_id；请求路径不能越权。相同 UUID 表示同一逻辑工作区，复制配置不得把两个独立工作区误配为同一身份。

### memory_operations

| 字段组 | 类型 / 约束 | 含义 |
|---|---|---|
| workspace_id, operation_id | UUID，联合 PK | 幂等逻辑调用；SAVE/RECALL 共用操作凭据 |
| kind, scope | SAVE/RECALL；CORE/ARCHIVAL | RECALL 固定 ARCHIVAL；未知枚举拒绝 |
| request_hash, raw_input | SHA-256 文本；TEXT | hash 含协议版本、workspace、kind、scope、原文；除U+0000在协议边界拒绝外，保留精确 Unicode 文本 |
| state | RECEIVED/RUNNING/COMMITTED/FAILED/ABORTED | 原文登记、处理、终态；详见下一节 |
| baseline_revision, committed_revision | BIGINT，可空 | 推理快照及提交结果；RECALL 不递增 namespace |
| owner_token, deadline_at | UUID，可空；TIMESTAMPTZ NOT NULL | 登记时即固定created_at+30秒期限；owner不能延长或自动重跑 |
| result_json, error_code | JSONB；固定错误分类，可空 | 持久receipt；所有终态带身份/hash，FAILED/ABORTED带memory_effects_applied=false；不接收远端任意错误文本 |
| created_at, started_at, completed_at | TIMESTAMPTZ | 时序与重启恢复 |

原始输入先于模型调用独立提交。相同 ID+hash 只读原状态；不同 hash 返回冲突。原始输入及终态结果不删除；状态字段允许合法迁移，但不改原文。RECALL 的查询原文作为调用审计保留，不进入记忆条目。

### memory_versions（追加历史）

| 字段组 | 类型 / 约束 | 含义 |
|---|---|---|
| version_id | UUID PK | 每次条目变更的唯一版本 |
| workspace_id, memory_id, scope | UUID、UUID、枚举 | 所有查询和约束携带工作区与范围 |
| operation_id, action_index | UUID、INTEGER | 联合唯一(workspace_id,operation_id,action_index)；指向已登记操作 |
| revision, created_revision | BIGINT | 此变更提交号、该条目首次提交号 |
| event | ADD/UPDATE/DELETE | DELETE 仅使当前投影失效，不删历史；CORE 只允许 ADD |
| old_content, new_content | TEXT，可空 | ADD 无 old、DELETE 无 new；每次动作原文均保留；含U+0000的生成内容提交前fatal |
| previous_version_id | UUID，可空 | 条目版本链；UPDATE/DELETE 必须接当前前驱 |
| changed_at | TIMESTAMPTZ | 提交时刻 |

索引按 workspace_id/memory_id/revision DESC/action_index DESC；同一次操作中同条目多步变更按 action_index 保留。工作角色对历史只允许 INSERT/SELECT，禁止 UPDATE/DELETE；旧版本、原始输入及 SDK NONE 处理的依据均可追溯。NONE 无内容变化时不造虚假内容版本，以 operation receipt 记录 NOOP。

### memory_current（有效投影）

| 字段组 | 类型 / 约束 | 含义 |
|---|---|---|
| workspace_id, memory_id | UUID，联合 PK | 当前有效条目 |
| scope, content | 枚举；TEXT NOT NULL | 核心原文或当前有效归档；外部条目须合法Unicode、不含U+0000、非空白且≤32KiB UTF-8 |
| version_id | UUID NOT NULL，关联 versions | 当前版本；工作区/条目身份必须一致 |
| created_revision, updated_revision | BIGINT | 稳定排序和可见性 |
| embedding | vector(D)，可空 | CORE 为 NULL；ARCHIVAL 为固定 D 维有限数值 |
| embedding_model, updated_at | 文本；TIMESTAMPTZ | 显式模型身份与时间 |

删除归档只删除 current 投影，versions 留 tombstone。文本相同但身份不同不能互相排除。禁止直接给应用暴露任意 SQL/UPDATE/DELETE；组件是唯一业务写入方。

### memory_call_audits

| 字段组 | 类型 / 约束 | 含义 |
|---|---|---|
| call_id | UUID PK | 内部调用编号 |
| workspace_id, operation_id, call_index | UUID、UUID、INTEGER | 与 SAVE/RECALL 原始输入和结果关联 |
| kind, phase, provider, model | LLM/EMBEDDING；文本 | 提炼/动作决策/归档向量/查询向量等阶段 |
| state | STARTED/COMPLETED/FAILED/UNKNOWN | 开始先落盘；崩溃可留下 UNKNOWN，不伪造结果 |
| started_at, completed_at, latency_ms | 时间、时间、BIGINT | 内部请求时序 |
| prompt_tokens, completion_tokens, total_tokens | BIGINT，可空 | Provider 未提供时为空，不伪造 0 |
| request_json, response_json, error_code | JSONB，可空；固定分类 | 受控业务证据，不包含 HTTP 认证头、密码或 API key |

开始记录失败则不发请求；结束审计失败则 latch fatal、禁止 SAVE 业务提交。它与 OryxOS llm_calls 是不同审计来源；不能声称自动写入 Java 审计表。

## 4. 保存状态与原子性

1. 鉴权/校验 → 单独事务插入 RECEIVED、原文及由数据库时钟确定的created_at/deadline_at（+30秒）；重复ID不重复执行，不延长原期限。
2. 仅对未过期RECEIVED条件更新，取得唯一owner_token并设RUNNING；沿用登记时期限，读取namespace revision与只读REPEATABLE READ快照。登记后、抢占前崩溃也有可判定的过期条件。
3. CORE 不调用推理/embedding，生成原文 ADD。ARCHIVAL 的 Mem0 只读快照并写请求级暂存；内部模型调用成败独立持久审计。
4. 校验fatal latch及预算：提炼≤64 facts、动作（含NONE）/暂存变更≤128，每条生成内容≤32KiB UTF-8；内部模型请求/响应各≤1MiB。ADD/UPDATE/DELETE逐项对账变化/history/SDK事件；NONE以已验证metadata-only更新归一化NOOP，不改current内容/recency。合法facts=[]可NOOP；坏JSON、超限、吞错或未解释空动作不能冒充NOOP。
5. 关闭只读快照，开启短写事务：锁 operation 和 namespace，验证 owner/state/期限及 baseline revision；冲突则不提交，不自动重跑 LLM。
6. 在同一事务中确定新revision/时间等字段、构造完整COMMITTED receipt并按发送序列化器验证≤1MiB（包含所有信封字段及replayed两种包装）；然后原子提交namespace、versions、current和receipt。任何预算/写入失败均回滚业务变化，另记匹配FAILED凭据；旧值此前已在版本链中，绝不在COMMITTED后才处理输出超限。

RECALL只读取当前归档快照并产生审计/终态结果，不写versions/current、不递增revision。对top20候选按完整序列化receipt预算取最长完整前缀，持久记录returned_count/truncated_by_bytes；无匹配正常为空，存在候选却连一条也放不下则明确FAILED，不能截正文或伪装无匹配。

结果可能已落库但没有可信终态（含丢失、畸形、身份/hash不符或不完整receipt）：按原ID查询，不能重放或仅凭HTTP码确定失败。COMMIT应答未知不得另写FAILED覆盖可能已提交结果。FAILED/ABORTED凭据的memory_effects_applied=false仅指业务投影，不否认输入/审计已留存。恢复在行锁下将过期RECEIVED/RUNNING标ABORTED、未完成审计标UNKNOWN；不重推理、不删历史，晚到worker受owner/state/期限约束。

## 5. 快照与历史隔离

快照捕获workspace revision R，有效期300秒。snapshot_id签名只绑定workspace/R/nonce/期限等快照身份，**不绑定scope或排序**，同一个ID跨CORE/ARCHIVAL复用。分页cursor另绑定snapshot摘要、workspace/R、scope、sort_id、最后排序键和不晚于快照的期限；两类payload须区分type并校验HMAC-SHA-256。完整字段见适配协议§5，不新增持久表。

跨请求从versions选每个memory_id在R时最后一个版本，排除DELETE，构造“当时有效”的视图；只能创建当前R的snapshot，不能指定历史revision。snapshot可跨scope读，cursor不能跨scope/snapshot/排序复用。

- CORE：按 created_revision/memory_id ASC 全量，分页 100 条且每页最多 1MiB；少于页大小不代表结束。
- ARCHIVAL 注入：同一 R 下按 revision/action_index/memory_id 选最近 100 条，再稳定升序展示。
- RECALL：当前ARCHIVAL的REPEATABLE READ快照，精确余弦分数DESC、同分memory_id ASC，score≥0，最多20条且受整个receipt的1MiB预算约束；返回完整排序前缀并显式标记字节缩减，无ANN或自建检索管线。
- 客户端校验 snapshot_id/revision、scope、唯一 ID、累计数和 complete/next_cursor 一致。缺页或期限不足则整体失败，不返回部分核心。

## 6. 模型演进与保护

本地四字段 schema 只做兼容增表；外部五表使用独立版本化迁移与最小权限角色。D 由显式 embedding 配置确定并锁进部署清单；模型或维度变更不得静默重写既有向量，需另行迁移设计。导入 Mem0 时产生的非业务配置文件固定在受控 MEM0_DIR，不充当业务状态。

API key、数据库密码、模型凭证、TLS 私钥和 cursor 签名密钥均走环境变量/企业 Secret，不进入上述业务 JSON 或仓库。历史不自动删除、压缩或纳入普通召回。新增表的运行验证、故障注入和安全扫描属于实施验收，当前尚未执行。
