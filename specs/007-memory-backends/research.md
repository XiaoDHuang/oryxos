# 007 Research：后端可行性与准入

日期：2026-08-30。依据：[spec.md](spec.md)、[批准范围](../../docs/decisions/007-memory-backends-scope.md)、宪法 v3.0.0。

**结论：原版 Mem0 OSS 直连不通过核心全量、历史先保全及内部审计门禁；用户已批准受控服务端适配和 HTTP 白名单追加范围。** 本轮完成暂存式适配的源码核验与 Phase 1 设计。下文保留原版缺口证据，并在 R9–R12 给出采用的机制；未实际部署、执行故障测试或验收 Mem0。

## R1. 稳定端口与默认兼容

**Decision**: core 的 MemoryService/MemoryScope 签名不变；memory 内增加 LongTermMemoryStore，确定采用 append(String, MemoryScope)、load()、recall(String)。MarkdownMemoryStore 委托既有 LongTermMemory，MemoryServiceImpl(LongTermMemory) 兼容入口保留，新增 Store 接入路径。只创建选中后端的实现，未知选择失败。

**Rationale**: 门面目前直接依赖文件类；适配即可保持 Prompt 顺序、最近历史和 Tool 参数。文件类已有原子写、共享路径锁、旧格式修复和 4000 Java char 视图裁剪，不应重写。

**Alternatives considered**: 不改 core 返回类型、不将实现搬到 core。共同查询方法不再承诺关键词专用语义；旧 recallByKeyword 仅保留在文件兼容实现。

**Evidence**: [核心端口](../../oryxos-core/src/main/java/com/oryxos/core/memory/MemoryService.java)、[门面](../../oryxos-memory/src/main/java/com/oryxos/memory/MemoryServiceImpl.java)、[文件类](../../oryxos-memory/src/main/java/com/oryxos/memory/LongTermMemory.java)、[配置](../../oryxos-memory/src/main/java/com/oryxos/memory/MemoryConfiguration.java)。

## R2. SQLite 数据与查询

**Decision**: 实体/Repository 在 storage、策略在 memory；四字段为 id/scope/content/created_at，不新增租户列。内容原文一条保存，不 trim 或拆行。UTC 固定毫秒精度通过字段专用 UtcMillisInstantConverter 映射为 SQLite TEXT 实际值，核心与查询按 (created_at ASC,id ASC)，窗口按 DESC 取 100 条后反转展示，见 [data-model](data-model.md)。

**Rationale**: 固定时间精度避免可变长度字符串排序问题，id 消除同时间歧义。字面检索采用参数化 instr(content,:keyword)>0 且限定 ARCHIVAL。[SQLite instr](https://www.sqlite.org/lang_corefunc.html#instr)、[LIKE 语义](https://www.sqlite.org/lang_expr.html#the_like_glob_regexp_match_and_extract_operators)。

**Alternatives considered**: 不用默认 LIKE/Containing/IgnoreCase/GLOB，避免大小写及通配差异；不为窗口删除旧行；不把 JPA 缓存内可读当作事务提交。写入并发可明确失败，但已确认成功的行不得丢失。

## R3. Schema、未选访问与工作区

**Decision**: 在共享 db/schema.sql 追加幂等增表/索引，保留原三表。未选后端“零数据访问”指 Memory 行数据的读取/写入与远端 I/O 为零，不禁止共享 Session/审计连接和 schema 元数据初始化。仅选中 SQLite Memory 时验证其结构并查询数据；IF NOT EXISTS 不代表会自动修复错误结构。

**Rationale**: boot 已扫描 storage 全包；init 只建空 DB，SQL 初始化在重运行时执行，并配置 defer-datasource-initialization。Store 构造器不得提前查表，启动就绪校验须依赖初始化完成。[Boot 3.5 初始化](https://docs.spring.io/spring-boot/3.5/how-to/data-initialization.html)、[SQLite CREATE TABLE](https://www.sqlite.org/lang_createtable.html)。

**Alternatives considered**: 单独 Memory schema 初始化器可达到连 DDL 都不触及，但多一个顺序入口，当前无行数据访问语义不需要。四字段表的隔离由不同工作区绑定不同 DB 实现，不支持两个工作区共用一个文件，也不擅加 workspace 列。

**Evidence**: [boot 扫描](../../oryxos-boot/src/main/java/com/oryxos/OryxOsApplication.java)、[application.yaml](../../oryxos-boot/src/main/resources/application.yaml)、[schema.sql](../../oryxos-storage/src/main/resources/db/schema.sql)、[InitCommand](../../oryxos-cli/src/main/java/com/oryxos/cli/InitCommand.java)。

## R4. Mem0 版本与协议

**Decision**: 不采用新算法作为自动替换实现。受控适配选定 v1.0.11 / SHA 144627c4ce5bc4db6acac17cbd158065f2b27a8d 为开发锁定基线，尚非生产准入。GitHub tag API 核对 v2.0.19 对应 dc82354e143c2581d505d581a00286d6ef8c3605；研究时 main 为 19cb89aff472325c707f64b2f34ae6afdbf7faf7。

**Rationale**: 新算法采用 ADD-only，不再在提炼阶段 UPDATE/DELETE，与用户批准的归档替换不同。[官方迁移说明](https://docs.mem0.ai/migration/oss-v2-to-v3)。v1 保留旧更新算法，但还存在后述门禁缺口。

**Alternatives considered**: 只新增后靠排序弱化旧事实、改成本地自建更新算法都会改动范围，不自行采用。旧 tag 也不能只锁 server：其 [requirements](https://github.com/mem0ai/mem0/blob/144627c4ce5bc4db6acac17cbd158065f2b27a8d/server/requirements.txt) 对 mem0ai 使用开放下界，SDK/传递依赖/镜像需一起锁定并做安全检查。本轮未完成旧版依赖安全验收。

**原生 HTTP 表面**: OSS 使用 /memories、/search、/memories/{id}/history，而非课件 /v1/ 前缀。v1 列表只接收身份，无 page/cursor；新版数量上限也不是完整性游标。[官方 REST 文档](https://docs.mem0.ai/open-source/features/rest-api)、[固定 v1 server](https://github.com/mem0ai/mem0/blob/144627c4ce5bc4db6acac17cbd158065f2b27a8d/server/main.py)、[核验新版 server](https://github.com/mem0ai/mem0/blob/19cb89aff472325c707f64b2f34ae6afdbf7faf7/server/main.py)。不能把上限调大当作全量核心方案。

## R5. 核心隔离与历史先保全

**Decision**: 原版直连不通过；现采用已批准的受控暂存适配及自有协议，不只靠 metadata.scope 和事后补记。

**Rationale**: v1 推理候选只按内建身份过滤，自定义 scope 不足以保护核心；列表默认有数量限制；更新/删除先改变向量状态后写 history，部分动作错误被捕获继续，结果不能证明完整成功。[固定 v1 SDK](https://github.com/mem0ai/mem0/blob/144627c4ce5bc4db6acac17cbd158065f2b27a8d/mem0/memory/main.py)。默认 graph 也须关闭，避免影响核心原文路径。

v1 history 只有提炼后的 old/new memory 等字段，无每次原始输入与操作关联。[v1 storage](https://github.com/mem0ai/mem0/blob/144627c4ce5bc4db6acac17cbd158065f2b27a8d/mem0/memory/storage.py)。新版增加 messages，但按 scope 清理至最近 10 条，也不是永久历史；此限制属于本轮核验新版，不适用于 v1。[新版 storage](https://github.com/mem0ai/mem0/blob/19cb89aff472325c707f64b2f34ae6afdbf7faf7/mem0/memory/storage.py)。

**Alternatives considered**: HTTP 200、调用结束后再保存旧值、盲目重试均不能证明历史已保全；客户端影子索引会形成第二份权威状态，且不能证明远端已有数据完整，不采用。

**已批准适配方向（不是第三方现成功能）**:

- 服务端强制工作区/scope 分区；可以研究 user_id=稳定工作区 ID、agent_id=固定核心/归档标识，但后者不是业务 Profile，还须校验变更目标归属。
- 原始输入、旧值与动作意图先持久化，再允许修改；操作 ID/状态凭据关联结果，未知结果查询状态而不是再次推理。
- 完整快照/游标读取核心；有效归档与历史分离。明确原子边界、并发冲突与崩溃恢复，不用“调 SDK”代替这些设计。

## R6. 下游数据边界与内部审计

**Decision**: 原生默认部署不准入。适配须锁定内网模型/embedding/存储，关闭 graph 和遥测，强制凭证/脱敏及网络限制；内部调用成败要有持久可关联证据，不冒充 OryxOS llm_calls。

**Rationale**: v1 有默认云端及环境路由；LLM 成功回调异常仅记日志，不覆盖失败调用，不能独自承担强制审计条件。[LLM 路由与回调](https://github.com/mem0ai/mem0/blob/144627c4ce5bc4db6acac17cbd158065f2b27a8d/mem0/llms/openai.py)、[Embedding 配置](https://github.com/mem0ai/mem0/blob/144627c4ce5bc4db6acac17cbd158065f2b27a8d/mem0/embeddings/openai.py)。遥测需在 import 前以 MEM0_TELEMETRY=false 关闭，并验证出口限制。[遥测源码](https://github.com/mem0ai/mem0/blob/144627c4ce5bc4db6acac17cbd158065f2b27a8d/mem0/memory/telemetry.py)。

**本地证据**: [ToolExecutor](../../oryxos-core/src/main/java/com/oryxos/core/react/ToolExecutor.java) 执行后才记录最终审计；[JpaToolInvocationAudit](../../oryxos-storage/src/main/java/com/oryxos/storage/audit/JpaToolInvocationAudit.java) 写入失败只记日志。即使参数包含原始输入，也不能作为外部变更前历史已保证的事务条件。本轮不改变前序审计行为。

## R7. 本地安全组合

**Decision**: memory 不依赖 tool，Sandbox 不搬入 core。已确定由 boot 组合 memory 的 MemoryOutboundGuard 与 tool 的 HttpWhitelistSandbox，仅提前补齐 HTTP 白名单，其余动作保持拒绝；公共类型在 Java 契约列全。

**Rationale**: [ToolConfiguration](../../oryxos-tool/src/main/java/com/oryxos/tool/ToolConfiguration.java) 当前默认 Sandbox 全拒绝；[Sandbox](../../oryxos-tool/src/main/java/com/oryxos/tool/sandbox/Sandbox.java) 在 tool，而 tool 已依赖 memory。空 guard 绕门禁，反向导入产生依赖环。

**Alternatives considered**: 全部提前实现第 24 节超出需要；默认允许内网不等于白名单；只检查保存会漏掉 prompt 加载和 recall 的出站。后续方案必须覆盖每次 I/O 和拒绝重定向。

## R8. 已有验证证据

本轮实际执行只读源码/文档核验和以下离线依赖解析，BUILD SUCCESS（2026-08-30）：

```powershell
mvn -o -pl oryxos-memory,oryxos-boot -am dependency:tree "-Dincludes=org.springframework:spring-web,org.springframework.data:spring-data-jpa,org.hibernate.orm:hibernate-core,org.xerial:sqlite-jdbc,org.junit.jupiter:junit-jupiter-api,com.squareup.okhttp3:mockwebserver"
```

未运行应用测试、OWASP 或真实 Mem0；未安装/启动 Python 服务、构建外部镜像或发送业务数据。算法候选不能列成已通过的第三方准入。

用户已批准原阻断对应的范围追加；以下是新的设计结论，未免除运行验证。

## R9. 请求级暂存而非 SDK 直接写入

**Decision**: 受控 StagedMemory 子类不调用原版构造器，只注入固定源码所需 config/prompts、受控 LLM/embedding、暂存 vector/history。归档只调用固定 `_add_to_vector_store(..., infer=True)`；核心直接原文事务。不开公共 add 的 graph 路径，不先 from_config 后替换对象。

**Rationale**: 已核验 [VectorStoreBase](https://github.com/mem0ai/mem0/blob/144627c4ce5bc4db6acac17cbd158065f2b27a8d/mem0/vector_stores/base.py) 和 [LLMBase](https://github.com/mem0ai/mem0/blob/144627c4ce5bc4db6acac17cbd158065f2b27a8d/mem0/llms/base.py) 可承接注入。SDK NONE 也可能直接更新metadata，必须截获全部vector写方法，不只拦三个memory helper；这是固定私有调用点集成，需摘要和结构回归防版本漂移。

**Alternatives considered**: 真实库先改再写history不安全；重写事实提炼算法超出范围。当前选暂存+对账，若机制harness不能证明写入完全拦截，停止复核而不是临时换算法。

受控wrapper先验证facts/动作JSON结构，调用或暂存异常先置fatal再抛出；即使SDK吞掉异常，外层仍拒绝提交。ADD/UPDATE/DELETE逐项匹配暂存变化、历史与SDK结果；NONE没有原生history/返回事件，单独匹配已验证决策和metadata-only暂存证据并归一化NOOP，不改current内容/recency。目标归属在staging层再次校验。合法空facts可NOOP；坏结果或未解释空动作不行。SDK的callback不作为成功条件。

导入仍会创建 [MEM0_DIR配置文件](https://github.com/mem0ai/mem0/blob/144627c4ce5bc4db6acac17cbd158065f2b27a8d/mem0/memory/setup.py)，须固定受控路径并关闭遥测；不能声称完全没有本地文件副作用。

## R10. 外部事务与快照

**Decision**: 外部PG同一库承载current/versions/operations/calls。原始输入先独立登记；推理读REPEATABLE READ快照，所有修改仅暂存。最终短事务锁operation与namespace，校验owner/期限/baseline revision，再原子提交current、追加版本和receipt。冲突不重推理；过期恢复只改状态。

**Rationale**: 数据库事务和行锁可表达该提交边界；不能只用默认READ COMMITTED跨多次查询拼一致视图，也不用非事务sequence当提交号。[PG事务隔离](https://www.postgresql.org/docs/17/transaction-iso.html)、[行锁](https://www.postgresql.org/docs/17/explicit-locking.html)。按追加版本构造当前revision快照及keyset分页，使Java完整读取不用长驻跨HTTP事务。

**Alternatives considered**: 不做SQLite与PG分布式事务、不把Java日志作业务history、不默认自动重试LLM。pgvector只提供现成余弦检索能力，当前不用ANN或自建向量索引。[pgvector](https://github.com/pgvector/pgvector/tree/8ee86c96f0fd72390f890aa8a336fda6d3ab4c6c)。

## R11. Java错误与成功确认

**Decision**: 新增受限MemoryOperationException，固定分类/UUID由AnnotatedToolAdapter精确映射成非重试失败，保留void/String签名；ToolExecutor不中途丢掉已验证的非重试失败明细。既有SQLite审计status保持failed，以错误分类区别timeout/unknown，不擅改审计端口。

**Rationale**: String错误会变成工具成功，普通异常又会丢失操作ID。固定final异常避免泄露任意远端message；中断标志不清除。正常Mem0完成可含NOOP，因此MemoryConfiguration用包内回复策略返回“记忆处理完成”；既有public构造入口及Markdown“已记住”不变，不能用旧文案暗示每次都新增事实。

**Alternatives considered**: 不透传所有getMessage、不改MemoryService返回值、不增加core错误类型。暂不为服务操作与Java审计新增跨库外键；服务内原始输入/调用/结果通过workspace+operation_id完整关联。

## R11a. 实现前一致性修复（I1/A1/A2/A3）

**Decision**: snapshot身份不含scope，同一个快照服务两个分区；cursor才绑定scope/排序与具体快照摘要。SAVE派发后只凭匹配持久终态确认，所有无效响应或查询失败仍按未知结果处理；不由HTTP码推断回滚。模型输出、条目和receipt均在提交前按实际序列化预算校验；RECALL返回完整排序前缀并标记字节缩减。部署绑定/origin采用协议§7.1的JSON schema，坏项、重复或Secret复用均拒绝。

**Rationale**: 创建snapshot无scope输入，不能要求其签名绑定scope；失真响应不证明业务未提交；原文UTF-8长度不等于转义后JSON长度；仅列环境变量名称不足以保证两端配置一致。这些修复不改核心签名、后端范围或宪法。

**Alternatives considered**: 不建两份时点不同的分区快照、不把畸形200直接判定未保存、不截断记忆正文或丢弃SAVE动作、不采用CSV/最后配置项覆盖的宽松绑定。验证落在T025–T028、T031–T032、T034–T035、T038、T042、T044–T046、T051、T056–T058，运行仍待实施。

## R12. 版本、证据与设计准入

已核验版本来源：[Python 3.12.14](https://www.python.org/downloads/release/python-31214/)、[FastAPI 0.141.1](https://pypi.org/pypi/fastapi/0.141.1/json)、[uvicorn 0.52.4](https://pypi.org/pypi/uvicorn/0.52.4/json)、[psycopg 3.3.4](https://pypi.org/pypi/psycopg/3.3.4/json)、[Mem0 1.0.11元数据](https://pypi.org/pypi/mem0ai/1.0.11/json)、[PostgreSQL 17.11](https://www.postgresql.org/docs/17/index.html)。pgvector tag 0.8.6对应SHA 8ee86c96f0fd72390f890aa8a336fda6d3ab4c6c。这里只验证版本存在和设计调用点，未假称这些组合已安装兼容或没有漏洞。

Phase 0产品/结构问题已解：新增组件获准、事务/失败/分页/身份/配置均在Phase 1契约定稿。实际依赖锁、镜像digest、暂存harness、真实PG/模型、Python/镜像扫描和网络证据属于计划中的运行门禁；未通过不得启用或封板。按 [quickstart](quickstart.md) 执行并保存证据，不用文档检查代替测试。
