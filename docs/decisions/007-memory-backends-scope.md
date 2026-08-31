# 007 Memory 三后端：范围批准与拆解

日期：2026-08-30；实施更新：2026-08-31。状态：**方案 B、clarify、受控 Python 适配/HTTP 白名单追加范围及实施均已获用户批准，一致性修复复审通过。US1已在`2a63e58`提交，SQLite已实现并通过本地验收，Mem0尚未实现。实际完成项及运行证据见[验收台账](../../specs/007-memory-backends/acceptance.md)，007未封板。**

本记录汇总四份事实源的本次决议及其实施准入，不替代事实源或后续 feature spec/plan。

## 1. 与 006 的关系

- 006 保留为文件式 Memory 稳定基线，归档提交 `3d60ee0`（`feat: archive feature 006 file-backed memory`）。其 [spec](../../specs/006-memory/spec.md)、[plan](../../specs/006-memory/plan.md)、[tasks](../../specs/006-memory/tasks.md) 不扩写为三后端。
- 006 已有 35/35 完成任务及全量验证证据；这些证据仅覆盖该提交的文件基线，不能计作 007 后端验收。归档提交已本地完成，尚未推送。
- 007 在 `codex/007-memory-backends` 分支上推进；治理/设计阶段只改文档，2026-08-31获准进入实现后开始修改对应源码、测试及显式schema。不得改动生产库。
- 一项新 feature 内分四个故事，不将每个后端拆成独立 feature。三者共享门面、选择配置、兼容和契约验收，分开立项容易把共同约束拆散。
- 本次用户批准依据：采用课件三后端方案；先同步四份事实源、宪法及 AGENTS；归档 006 后继续拆解 007。此批准不授权未列出的模块拆分或未经核验的外部数据传输。

## 2. 已批准范围

| 项目 | 决定 |
|---|---|
| 默认行为 | Markdown，本地 `memory/MEMORY.md`，沿用 006 兼容性与断言 |
| 可选后端 | SQLite / 企业自托管 Mem0；无默认云服务或后台遥测 |
| 选择 | `memory.backend=markdown\|sqlite\|mem0`，唯一选择、启动生效；非法值失败 |
| 核心端口 | 保留 core 中 `MemoryService`、`MemoryScope` 及既有签名 |
| 内部实现 | memory 中 `LongTermMemoryStore` 与 Markdown/SQLite/Mem0 适配器；保留 `LongTermMemory` 兼容入口 |
| SQLite 数据 | storage 中新增 `memory_entries` 实体/仓储/显式迁移，不重建 Session/审计表 |
| 模块 | 保持 9 个；安全组合接线须在 plan 定案，不允许 memory 反向依赖 tool |
| 写入触发 | 仍由显式 `save_memory` 决定；不增加会话结束/上下文压力触发器 |
| Mem0 归档处理 | 用户已在 clarify 批准自动提炼、合并和替换；当前召回使用有效事实，核心及本地后端原文规则不变 |
| Mem0 变更历史 | 用户已批准保留原始输入及被合并/替换旧归档，可追溯保存操作与有效结果；常规召回/自动归档注入不读取历史副本 |
| 外部受控适配 | 用户已批准 integrations/mem0-adapter/ Python 组件；固定 SDK 暂存推理、PG 原子提交、操作凭据及自有 oryx-memory-v1 协议，不新增 Maven 模块 |
| HTTP 安全提前接入 | 用户已批准 MemoryOutboundGuard / HttpWhitelistSandbox / boot 组合；只实现 HTTP 白名单，其他动作仍拒绝，不扩大为完整 Sandbox |
| 隔离 | 长期记忆仍为工作区级共享；CORE/ARCHIVAL 是分区，不是租户身份 |
| 切换 | 不迁移、不删除、不双写旧后端；回切仍可访问其旧数据，空目标需明确说明 |

不纳入 007：情景记忆、Memory Wiki、知识图谱、自建向量索引、OryxOS独立记忆压缩任务、自动提炼触发器、多租户、Web 管理 API、缓存/热切换、自动跨后端迁移、Letta/外部 Agent 运行时。已批准的Mem0显式归档保存内处理不在排除项内。

## 3. 故事拆解（供正式 spec 采用）

| 故事 | 用户价值与交付边界 | 依赖 | 独立验收 |
|---|---|---|---|
| US1 / P1 默认兼容与选择 | 现有使用者无需改配置即可继续保存/回忆；引入 Store 与 Markdown 适配、唯一选择及非法配置失败 | 006；治理同步 | 不设置 backend 时仍走文件；006 断言保持；不连接 Mem0；旧数据与核心接口不变 |
| US2 / P1 SQLite | 使用者选择本地结构化存储，重启后仍可访问同一工作区记忆 | US1；显式迁移设计 | 真实临时 SQLite，核心完整、归档最新 100 条、全量关键词查询；旧库/重复启动安全 |
| US3 / P2 自托管 Mem0 | 使用者在批准的数据域内显式选用服务化记忆与语义召回 | US1；全部 Mem0 plan 门禁 | 真实适配器 + HTTP 替身验证协议；锁定自托管版本的受控冒烟；scope/分页/可见性/失败/安全全覆盖 |
| US4 / P1 统一验收与切换说明 | 使用者知道后端差异、风险和切换结果；维护者有可重复的整体验收 | US1–US3 | 三后端契约矩阵、禁用后端零访问、跨新 Session/重启、Prompt 顺序及 Tool 审计；完整质量门禁 |

US4 的契约测试定义先于或伴随 US1–US3，表中依赖指最终验收收口，不意味着到最后才写测试。US2/US3 在 US1 后可独立开发，但本记录不自动授权并行 Agent 或创建新任务。

### US1：默认行为不变

1. 清点 core 端口、`MemoryServiceImpl` 构造入口、`LongTermMemory` 四方法和所有调用点，确定兼容适配。
2. 定义 Store 的保存/加载/归档查询契约及异常语义；内部名称由 plan 固化，不将语义搜索命名成“关键词等价”。
3. Markdown 适配优先委托 006 文件实现，保留原子写、共享锁、不缓存、旧格式迁移、缺失区块修复。
4. 设计 Spring 唯一选择与自定义 Bean 的冲突处理；不选 Mem0 时不要求其凭证、不创建远程连接。
5. US1 默认路径通过既有回归；SQLite/Mem0 未交付前，不能让已选但不可用的后端静默落回文件。

### US2：SQLite 原文与窗口

1. `memory_entries` 仅有 `id` / `scope` / `content` / `created_at`；结构见技术方案 §9.2。
2. 定义幂等建表/迁移及现有数据库升级；验证 Session/Tool/LLM 审计数据不变。
3. 核心全量原文；归档加载最近 100 条，排序以时间及 id 稳定消歧，输出顺序在 plan 固定。
4. 归档查询扫描全部历史、排除 CORE；关键词参数化且明确 `%` / `_` 的字面语义，不把 SQL 通配符误当用户意图。
5. 覆盖并发保存、重启、Unicode/空输入/非法 scope/失败回滚和禁用后端不访问该表。

### US3：Mem0 先核验，再接线

1. 锁定自托管服务版本、部署形态及真实 REST 契约；核验请求/响应、认证、metadata/scope、分页、查询能力。
2. 明确稳定工作区身份及 CORE/ARCHIVAL 映射；不能按 Profile 分区改变既有共享语义，也不能让两个工作区串数据。
3. 定义核心全量原文路径、归档加载窗口/稳定排序/分页耗尽判定，以及语义召回的独立验收标准。
4. 核验保存的同步/异步行为；只有下一轮可见才返回成功。有界等待、超时、不确定写入和重试幂等策略在 plan 决定。
5. 接入每次出站前必需的安全检查，覆盖重定向/目标变化；失败关闭。mem0 客户端、服务及模型/embedding/存储下游均纳入批准数据域。
6. 按 clarify 两项决议启用归档自动提炼、合并和替换，持久保留原始输入及旧版本用于追溯；核心禁止推理改写，常规召回/自动归档注入排除历史记录。plan 验证历史落位、变更关联、失败保全及有效状态写后可见性；服务内部模型调用应有可验证审计来源，不能把 Tool 成功记录当成全部推理审计。
7. 默认测试调用真实 `Mem0MemoryStore`，以 HTTP 替身模拟分页、错误和延迟；最终做锁定版本自托管实例冒烟。没有实例时如实标记未验收，不用假 Store 代替。

### US4：测试矩阵与用户说明

| 验收维度 | Markdown | SQLite | 自托管 Mem0 |
|---|---|---|---|
| 核心记忆 | 原文全量，不裁剪 | 原文全量，不裁剪 | 核验完整读取所有页，禁止推理改写 |
| 归档注入 | 最近 4000 Java char | 最近 100 条，稳定顺序 | plan 锁定窗口/分页/排序，不能默认无限量或默认第一页 |
| 归档检索 | 全量包含匹配、文件顺序 | 全量参数化关键词查询 | 经核验语义查询 + 强制 scope 过滤 |
| 写入可见 | 保存成功后下一轮读取 | 提交成功后下一轮读取 | 同步确认或有界等待后可读；不静默最终一致 |
| 状态恢复 | 同一路径新实例 | 同一库新上下文 | 同一工作区身份、锁定服务状态恢复 |
| 故障 | 文件 IO 可观测 | SQL/事务故障可观测 | HTTP/鉴权/超时/畸形响应/部分页失败可观测 |

共同验收另含：空输入、非法 scope、CORE 不进入 recall、保存/回忆均经既有 ToolExecutor 审计、无第二套 Session、Prompt 顺序不变、不改 `USER.md`、未选后端零访问。切换测试不得假定自动迁移；应验证旧数据仍在、目标独立、回切可恢复原视图。

## 4. plan 必须关闭的决策项

| 编号 | 必须解决的问题 | 完成证据 / 未完成时的限制 |
|---|---|---|
| G1 | Store 内部签名与兼容入口 | 已在 Java 契约定稿，core签名不变 |
| G2 | SQLite初始化/时间/查询 | 已在 data-model 定稿，UTC毫秒转换与幂等DDL |
| G3 | 协议/分页/scope | 已采用自有oryx-memory-v1及revision分页，原版直连仍不允许 |
| G4 | 暂存推理/历史/幂等 | 已在五表模型和状态机定稿；真实机制与故障测试作为首批实施门禁 |
| G5 | 出站安全与无环依赖 | 用户已批准并固定三模块组合，公共类型清单已列全 |
| G6 | 身份与配置 | UUID工作区/凭证绑定、期限/错误/限额已固定 |
| G7 | 下游与内部审计 | 设计明确强制配置、受控wrapper和持久审计；实际部署及网络证据仍待实施验收 |

G1–G7 的设计问题已由获准方案闭合，可以据此生成实现/验证任务。原版服务缺口未被豁免；先完成锁定SDK暂存harness，机制失败即停。实际运行启用与封板按 plan 的 R1–R5 取得真实安全、事务、网络及审计证据，不能把设计完成当作运行通过。

## 5. 后续产物与准入顺序

1. 本次：宪法 v3.0.0、四份事实源、AGENTS/CLAUDE 软链、README、课程说明与技能映射同步；006 规格不改。
2. `/speckit-specify` 已完成：生成正式 `specs/007-memory-backends/spec.md` 和质量清单，复用 `codex/007-memory-backends` 分支，`.specify/feature.json` 已指向 007。最初的保守原文默认已被后续 clarify 决议取代。
3. `/speckit-clarify` 已完成，2 问 2 答：启用 Mem0 归档自动提炼/合并/替换；保留原始输入与旧归档供追溯，不参与常规召回。规格的场景、实体、要求及成功标准已同步，历史未保全不得虚报完整成功。
4. `/speckit-plan` 已完成设计：用户批准新增外部适配及HTTP白名单，产出 [research.md](../../specs/007-memory-backends/research.md)、[data-model.md](../../specs/007-memory-backends/data-model.md)、[contracts](../../specs/007-memory-backends/contracts/mem0-adapter-api.md)、[quickstart.md](../../specs/007-memory-backends/quickstart.md)。原版Mem0不合格的源码证据保留，采用暂存+原子提交设计而非放宽规格。
5. `/speckit-tasks` 已完成：75项任务（US1=9、US2=10、US3=39、US4=8、准备/基础/收尾=9），17项可并行；保留机制/事务/运行门禁和最终Spark回归。下一步正式一致性分析，再等实施确认。
6. 实现后：显式集成测试 + 前序回归 + 不跳插件的 `mvn clean verify` + 实现一致性分析。缺少真实远端验收时不得将 007 全部标完成。

治理同步、specify、clarify、plan及tasks生成时尚无实施，只有只读源码/版本核验和离线dependency:tree。2026-08-31按用户确认进入实施，当前源码及测试已独立推进；实际记录见007验收台账，不能借用006绿灯。

本次文档检查：`git diff --check`、006 应用/规格零差异、宪法版本日期、范围记录本地链接及课程技能 frontmatter 未变均通过。技能自带 `quick_validate.py` 因环境缺少 PyYAML 未能执行（离线缓存也无此依赖），没有为此更改技能元数据或安装依赖；这不是应用测试通过证据。

## 6. 事实源

- [IndustryResearch §5.8](../IndustryResearch.md)：运行时与外部记忆层定位。
- [DemandAnalysis §5.5](../DemandAnalysis.md)：需求、范围与验收边界。
- [TechnicalSolution §5/§9/§10](../TechnicalSolution.md)：端口、存储模型、配置与依赖方向。
- [AiProgrammingGuide §3.2/§4.3](../AiProgrammingGuide.md)：治理修订与故事拆解。
- [Constitution v3.0.0](../../.specify/memory/constitution.md)：硬门禁。
