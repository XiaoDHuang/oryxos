# Implementation Plan: 可切换的三后端长期记忆

**Branch**: `codex/007-memory-backends` | **Date**: 2026-08-30 | **Spec**: [spec.md](spec.md)

**Input**: `specs/007-memory-backends/spec.md`，含 2 项归档处理与历史保留决议。

**Status**: **DESIGN COMPLETE — 追加范围已获用户批准，Phase 0/1 设计完成，可进入 tasks；尚未实现或通过运行验收。**

**实施进度（2026-08-31）**：用户已确认进入implement；下文保留计划阶段的设计描述。当前实际完成项以[tasks](tasks.md)和[acceptance](acceptance.md)为准，不把单个故事完成当作007封板。

## Summary

006 的 `3d60ee0` 文件基线不变。007 默认 Markdown、可选 SQLite 和自托管 Mem0，保留核心端口及 9 个 Maven 模块。Mem0 显式归档保存须自动提炼/合并/替换，原始输入与旧版本须持久保全，常规召回排除历史。

本地后端可在既有边界内设计；但仅增加 Java REST 客户端直连原版 Mem0 server，不能兑现核心全量、历史先保全和内部调用审计。具体源码证据见 [research.md](research.md)。

用户已明确批准保持 007 一个 feature，增加与 Mem0 同部署的受控 Python 适配组件，以及 tool/boot 中必需的 HTTP 白名单接线。设计不再依赖原版 REST 的缺失能力：固定 SDK 只在请求级暂存对象上生成变更，外部 PostgreSQL 单事务提交有效状态、追加历史和操作凭据。核心保存完全绕过推理。

本轮产出是实现契约，不是宣称新组件已经存在。按 [data-model](data-model.md)、[Java契约](contracts/memory-contract.md)、[适配协议](contracts/mem0-adapter-api.md) 和 [验证指南](quickstart.md) 生成 tasks 后，仍须用户确认实施。真实安全/部署证据是启用与封板门禁，不能以设计通过替代。

## Technical Context

**Language/Version**: OryxOS JDK 21（本机21.0.11）；外部适配 Python 3.12.14。Python组件是获准的可选外部交付物，不是第十个Maven模块。

**Primary Dependencies**: 本轮离线依赖树核验：Spring Boot 3.5.16、Spring AI 1.1.8、Spring Web 6.2.19、Spring Data JPA 3.5.13、Hibernate 6.6.53.Final、SQLite JDBC 3.53.2.1。不升级现有 BOM。memory 尚无 Spring Web 编译依赖；后续若使用 RestClient，应显式加入现有 BOM 管理的 spring-web。

**External Dependencies**: mem0ai 1.0.11 / SHA 144627c4ce5bc4db6acac17cbd158065f2b27a8d；FastAPI 0.141.1、uvicorn 0.52.4、psycopg 3.3.4；PostgreSQL 17.11 + pgvector 0.8.6。开发测试工具基线 pytest 9.1.1、pip-audit 2.10.1。官方版本已核验，但完整依赖解析/uv.lock、SDK文件摘要与镜像digest须在首批实现任务生成并扫描；未通过时禁止启用，不自动换到ADD-only版。

**Storage**: Session/审计三表与本地memory_entries留在SQLite；外部PG五表为memory_namespaces、memory_operations、memory_current、memory_versions、memory_call_audits。原文先登记；current/versions/COMMITTED receipt在单事务提交，历史不删。没有跨SQLite/PG分布式事务，不把Java事后审计当写前保障。

**Testing**: JUnit Jupiter 5.12.2、真实文件 SQLite、ApplicationContextRunner、MockWebServer 4.12.0。后续测试必须经过真实适配器，远端传输替身不代替真实自托管冒烟。本轮未运行应用测试或完整 verify。

**Target Platform**: 项目既定 Linux 部署和 Windows 开发环境；外部记忆服务不成为默认本地路径的启动条件。

**Project Type**: 9 模块 Java 单体能力增量；已批准将可选外部适配纳入 integrations/mem0-adapter，不新增 Maven 模块。

**Performance Goals**: 延续项目目标，不静默截断核心。Java连接3s、读取30s、默认逻辑总期限40s；服务处理30s。snapshot有效300s，页≤100条/实际JSON1MiB；归档窗口100条、召回≤20条并按字节返回完整前缀。生成facts≤64、动作/暂存变更≤128、单条≤32KiB、内部模型请求/响应≤1MiB；成功receipt预算在COMMITTED前校验。超限明确拒绝，不隐式丢弃SAVE动作。

**Constraints**: 同步阻塞、核心公共签名不变、唯一后端、未选后端无记忆数据访问、显式建表、错误可观测、无默认外发。历史保全与有效状态可读同时满足才可成功。

**Scale/Scope**: 工作区级共享而非多租户；Markdown 归档最近 4000 Java char、SQLite 最近 100 条、Mem0 核心全量。仍包含 US1–US4，Mem0 P2 不代表可以跳过交付。

## Constitution Check

*GATE: Phase 0 前核对范围；研究发现无法满足的原则立即阻断 Phase 1。*

| 原则/门禁 | Phase 0 结果 |
|---|---|
| I 自实现 ReAct、II 禁自动 Tool、III 显式 Provider | 设计通过：控制面不变，外部仅记忆处理 |
| IV Profile/上下文边界 | 设计通过：scope与工作区身份不派生第二套Profile |
| V 审计落库 | 设计通过：Java旧审计保留；服务独立持久调用审计，提交前检查；待故障验收 |
| VI 安全与数据边界 | 设计通过：缺guard/Secret失败、HTTPS/白名单、强workspace绑定、禁遥测云回退；待实际部署证据 |
| VII 同步执行 | 设计通过：同步边界+虚拟线程限时等待，无固定池/CF编排/并行Tool |
| VIII 状态外置、Memory完整性 | 设计通过：请求暂存、PG原子提交、追加历史、revision快照全量；待真实事务验证 |
| 模块与职责审批 | 用户已批准外部适配及HTTP白名单；9模块不变，事实源/AGENTS同步 |
| 006 稳定提交 | `3d60ee0`已归档；本轮未改006或应用代码 |

原版直连的设计失败没有被豁免，而是由获准的适配机制替代。宪法v3.0.0已允许受控外部记忆，本次不修改原则或降版本。表中“设计通过”仅表示有闭合方案与可追踪验收，不能推断实现、安全扫描或真实运行已通过。

### G1–G7 处置

| 编号 | 当前结果 | 后续要求 |
|---|---|---|
| G1 端口与Store | 设计闭合 | memory-contract第1/2节，保留void/String和旧构造入口 |
| G2 SQLite | 设计闭合 | data-model第2节，字段专用UTC毫秒转换、instr查询、初始化排序 |
| G3 协议/分页/scope | 自有协议已定 | snapshot无scope、cursor绑scope/具体snapshot，revision-keyset分页；操作凭据匹配身份/hash |
| G4 历史/可见性/幂等 | 暂存机制已核验并定稿 | data-model第3–5节，单事务与owner/revision检查；先做机制harness |
| G5 安全/无环 | 接线定稿 | MemoryOutboundGuard→boot→HttpWhitelistSandbox，无memory→tool边 |
| G6 配置/身份 | 契约定稿 | JSON绑定/origin格式与重复策略、非nil UUID/规范Secret、预算和失败确认状态机明确 |
| G7 下游/审计 | 设计闭合；运行证据待实施 | 明确外部Secret、允许origin、审计表和失败闭锁，运行启用受R门禁 |

## Project Structure

### Documentation (this feature)

已有 spec.md、checklists/requirements.md、plan.md、research.md、data-model.md、contracts/memory-contract.md、contracts/mem0-adapter-api.md、quickstart.md；[tasks.md](tasks.md) 已由speckit-tasks生成，75项待一致性分析及实施确认，尚未执行。

### Source Code (repository root)

以下为确定的计划落位，不是本轮新增代码：

- `oryxos-core/src/main/java/com/oryxos/core/memory/`：保留 MemoryService/MemoryScope，仅后续同步查询 Javadoc。
- `oryxos-memory/src/main/java/com/oryxos/memory/`：保留 LongTermMemory，计划增加 Store 与三适配器、改造门面和条件配置。
- `oryxos-storage/src/main/java/com/oryxos/storage/memory/`：计划 MemoryEntry/Repository，四字段不增加工作区或租户列。
- `oryxos-storage/src/main/resources/db/schema.sql`：计划追加幂等 Memory DDL，旧三表不重建。
- `oryxos-tool/src/main/java/com/oryxos/tool/sandbox/`：HttpWhitelistSandbox，仅HTTP提前，其余默认拒绝。AnnotatedToolAdapter精确映射MemoryOperationException。
- `oryxos-core/src/main/java/com/oryxos/core/react/ToolExecutor.java`：保留已验证的不可重试失败与操作编号，不通过清除中断标志继续工作；不改公共签名。
- `oryxos-boot/`：MemoryOutboundConfiguration组合guard与Sandbox；集成测试覆盖全部后端。
- `integrations/mem0-adapter/`：计划pyproject.toml、uv.lock、compose.yaml、Dockerfile、src/oryx_mem0/、migrations/、tests/；可选独立交付，不新增Maven模块。

外部src/oryx_mem0内分app/contracts、engine/staging、storage、audit、security职责；StagedMemory使用固定私有SDK调用点，SQLiteManager/原版Memory构造器不得初始化真实业务存储。SDK导入产生的非业务配置固定在受控MEM0_DIR。

**Structure Decision**: 不把 Sandbox 搬入 core，不新增 Maven 模块；默认本地运行不依赖外部适配。

## Complexity Tracking

| 已批准复杂度 | 为什么需要 | 较简单替代不能满足的原因 |
|---|---|---|
| 外部 Python 受控 Mem0 适配 | 完整读取、硬隔离、先保全、操作状态与内部审计 | 事后补写无法恢复已丢失旧值，数量上限不能证明全量 |
| 007 提前接入 HTTP 白名单和 boot 窄接线 | 可执行外部能力必须有真实校验 | 当前全拒绝；空 guard 与循环依赖均不可接受 |
| 安全锁定与真实环境验证 | 算法语义匹配不代表部署安全 | 不能使用开放依赖范围、默认云配置或假 Store 作为证据 |

用户已批准前两项范围追加；第三项仍为必需验收，不是可绕过的例外。适配只做协议/事务兼容，不另造Agent循环或向量检索算法。

## Delivery Sequence & Gates

1. US1：Store、选择配置、默认Markdown兼容；先验证006断言和未选后端隔离。
2. US2：SQLite实体/转换/仓储/幂等DDL，真实事务和重启验收。
3. US3a：建立锁文件与固定SDK暂存harness；校验NONE直写、吞错、审计失败和所有写入拦截。机制验证未通过，不继续完整远端适配。
4. US3b：五表迁移、operation状态、原子提交、快照/查询、HTTPS/认证；Python真实PG故障集成。
5. US3c：Java Mem0MemoryStore、HTTP白名单/guard和受限错误；已派发SAVE未取得匹配持久终态一律查原ID或报UNKNOWN，不被畸形200/5xx等诊断覆盖，不重放。
6. US4：共同/差异契约、6向切换、跨重启、前序回归、真实内网模型与全路径安全；显式integration加Java/Python/镜像完整门禁。

US4验收定义伴随各步骤，不最后补测试。代码和测试编写主模型；最终回归执行遵循用户的Spark要求，本轮没有执行回归。

| 运行/封板门禁 | 必须取得的证据 |
|---|---|
| R1 依赖可重复与安全 | 完整锁图、固定SDK文件摘要、镜像digest；pip-audit/镜像扫描，无未处置门禁问题 |
| R2 暂存与事务机制 | SDK吞错不能提交、历史失败不覆盖、冲突不重推理、崩溃/响应丢失正确恢复 |
| R3 安全接线 | 缺Secret/guard拒绝，TLS/域名/跨scope负例，默认遥测/云出口被阻断 |
| R4 真实后端验收 | 内网LLM/embedding完整审计，golden事实集、核心全量、历史留存、6向切换 |
| R5 全仓质量 | 保留006断言，显式integration、无跳插件的mvn clean verify、Python和镜像证据 |

这些证据当前均未声明通过。计划闭合后可生成tasks并按用户确认实现验证；未通过R门禁不得实际启用Mem0部署、归档或宣称007完成。若固定SDK安全/行为无法满足，应重新决议，禁止静默降级规格。
