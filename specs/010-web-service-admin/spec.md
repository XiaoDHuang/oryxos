# Feature Specification: Web Service 与第一版管理平台

**Feature Branch**: `026-lesson26-web-service`

**Created**: 2026-09-05

**Status**: 010 原交付已提交；第 27 节串联补充已验收（2026-09-06），T029–T041 完成，尚未提交。结果见 lesson27-acceptance.md；010 原报告的其他人工项不据此自动勾选。

**Input**: User description: "第26节需求：Web Service 与第一版管理平台——把运行时内核包装成 REST API 对外门面，外加一个只读管理台"

## Clarifications

### 第 27 节串联决议（2026-09-06）

- 用户批准 D27-01：沿用分页 `listSessions(page,size)`、既有创建字段与响应信封，以详情的 `totalMessages` 对账；不增加重复 `listRecent`，不新增列表 status 筛选或消息数列。
- 用户批准 D27-02：`oryxos.root` 映射现有装配点，默认 `.oryxos`；默认 SQLite 随根派生，显式数据源配置优先，不引入新运行时类型。
- 用户批准 D27-03：显式 Bootstrap 缺失或读取失败必须报错，修正旧测试的“WARN 后继续”预期；空引用列表保持可用。
- 用户进一步要求管理台以 website 实际风格为准；与课件或 skill 不一致时优先 website。

## 第 27 节人推串联的附加验收范围

- **L27-001**：显式 `mock` Provider 无 key、无模型网络访问，只有模型为合成脚本；一次“记住：”产生两轮 ReAct、一次真实 save_memory、四条角色历史、SQLite 两条 LLM/一条 Tool 审计。未配置 mock 时不自动注册。
- **L27-002**：临时 `oryxos.root` 同时隔离 Profile/上下文/Memory/MCP 配置和默认数据库；默认根兼容既有用法，白名单不因根改变而自动扩大。显式引用上下文缺失或不可读须在模型调用前失败。
- **L27-003**：真实 HTTP 与 CLI 复用同一引擎和持久化；查询接口可读同一会话、完整角色/工具结果、真实 Memory 及工具清单，新的会话能读到已保存记忆。
- **L27-004**：Provider 故障、Sandbox 拒绝、真实工具执行失败均保留失败审计，后续请求仍能正常处理。
- **L27-005**：真模型 HumanTriggerFlowIT 显式执行并打 integration 标签；CLI/REST 天气各两次模型调用和一次 http_get、不改 Memory，保存与跨会话记忆另行取证。缺环境时不能用 mock 标绿。
- **L27-006**：管理台会话 ID 可点击读取现有详情接口；加载/空/错误/重试完整，保留只读和官网样式。锁文件安装后构建，单 JAR 托管真实 admin 根和子路由；README 无 key 流程可重演。
- **L27-007**：两个无 key 全链路测试进入默认 gate，完整 mvn clean verify 不跳插件，前序测试保留；真实环境结果和人工项分别记录，不自动提交或推送。

010 的原 11 端点基线仍由下方 FR/SC 定义。既有 `7b1eceb` 的三项 Sandbox 管理操作先于本节存在，不是第 27 节新增端点，也不属于只读管理台页面；其文档收口应独立追踪。

### Session 2026-09-05

- Q: 归档与会话列表能力放哪一层？（现状：SessionManager 端口只有 getOrCreate/get/save，归档从未实现） → A: 扩展 core 的 SessionManager 端口——新增归档与只读列表方法（纯加法，不改既有三个方法签名），web 层只依赖 core，不新增 web→storage 依赖边。
- Q: 对已归档会话再发消息（POST /sessions/{id}/messages）应如何响应？ → A: 按 400 拒绝（"会话已归档"）——归档=关闭但历史仍可读；404 只保留给不存在的会话。端口扩展需让会话视图携带状态以支撑该判定。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 业务系统连续对话（会话管理） (Priority: P1)

业务系统（运维平台、客服系统等）通过 HTTP 与某个 Agent 进行连续多轮对话：先创建会话拿到会话标识，然后多次发送消息，过程中随时查询对话历史，用完归档会话。

**Why this priority**: 连续对话是业务系统集成 Agent 的最主要路径；没有它，Web Service 的核心价值（让业务系统接入 Agent）不成立。

**Independent Test**: 只实现会话管理端点即可独立验证：创建会话 → 发消息 → 查历史 → 归档，全链路走通即交付价值；历史查询与 CLI 入口共享同一份会话存储。

**Acceptance Scenarios**:

1. **Given** 服务已启动且存在已注册的 Profile，**When** 业务系统请求创建会话（指定 Profile、用户标识），**Then** 返回会话标识，且同一会话三元组重复创建时幂等返回同一会话。
2. **Given** 已存在的会话，**When** 发送一条合法消息，**Then** 返回 Agent 的最终回复，且该轮处理与 CLI 入口走同一个引擎方法、审计记录照常落库。
3. **Given** 已存在的会话，**When** 查询会话历史，**Then** 返回对话历史，最多返回最近 100 条。
4. **Given** 已存在的会话，**When** 归档该会话，**Then** 会话标记为归档状态，之后查询可见归档结果。
5. **Given** 已归档的会话，**When** 向它发送新消息，**Then** 按参数错误（400）拒绝，提示会话已归档，历史不受影响。
6. **Given** 服务已启动，**When** 请求会话列表，**Then** 返回已持久化会话的只读列表（供管理台渲染）。

---

### User Story 2 - 业务系统一次性无状态调用 (Priority: P2)

业务系统不关心多轮上下文，只想把一条消息丢给指定 Agent 并同步拿到结果（如"总结这段日志"）。

**Why this priority**: 一次性调用是第二常用路径（stateless 短任务），但价值建立在会话与引擎之上，可独立叠加。

**Independent Test**: 只对指定 Agent 名发一次调用请求，验证同步返回最终结果，即可独立交付"无状态调用"价值。

**Acceptance Scenarios**:

1. **Given** 存在已注册的 Profile，**When** 业务系统按 Agent 名发起一次性调用，**Then** 系统临时建立一次性会话、跑完处理流程并返回最终回复。
2. **Given** 不存在的 Agent 名，**When** 发起一次性调用，**Then** 返回资源不存在错误（404）。

---

### User Story 3 - 信息查询与系统状态 (Priority: P2)

业务系统或运营人员查询当前运行的 Agent 配置（Profile 列表）、长期记忆内容、可用 Tool 列表，以及系统健康/版本/Provider 连通状态。

**Why this priority**: 信息查询是管理台与对接调试的基础，但不承载对话主链路，排在会话与调用之后。

**Independent Test**: 分别请求 profiles/memory/tools/health/info 五个查询端点，核对返回内容与运行时真实状态一致，即可独立验证。

**Acceptance Scenarios**:

1. **Given** 服务已启动，**When** 请求 Profile 列表，**Then** 返回全部已注册 Profile 的摘要信息。
2. **Given** 长期记忆已有内容，**When** 请求记忆查询，**Then** 返回当前选定后端的全量记忆视图（核心 + 归档窗口）。
3. **Given** 服务已启动，**When** 请求 Tool 列表，**Then** 返回全部已注册 Tool 的名称与描述。
4. **Given** 服务已启动，**When** 请求健康检查与系统信息，**Then** 前者返回可用状态，后者返回版本信息与各 Provider 连通状态。

---

### User Story 4 - 只读管理平台与可复用风格规范 (Priority: P3)

运营人员打开 `/admin` 管理台，通过左侧导航查看会话列表、Profile 列表、Tool 列表、长期记忆、运行状态五个页面；所有数据来自上述 GET 端点，整站只读。同时把管理台的视觉与工程约定固化成项目内可复用的生成规范，供后续加页（如 30 节 Agent 管理页）复用。

**Why this priority**: 管理台是 API 完备性的验证器与运维视窗，价值真实但依赖前面所有端点就位。

**Independent Test**: 启动服务后打开 /admin，五个页面渲染真实数据、无写操作入口、子路由刷新不 404，即可独立验收。

**Acceptance Scenarios**:

1. **Given** 服务已启动且管理台静态资源已构建，**When** 访问 /admin，**Then** 呈现左侧导航五项的管理界面，各页面调用对应 GET 端点渲染成表格或文本。
2. **Given** 某个 GET 端点返回错误，**When** 管理台收到统一错误 JSON，**Then** 页面显示其中的 message，且空数据/加载中/错误三态均有明确占位，不白屏。
3. **Given** 管理台的任意子路由，**When** 直接刷新浏览器，**Then** 页面正常加载（不出现 404）。
4. **Given** 项目仓库，**When** 查阅管理台风格规范文件，**Then** 能找到固化的设计 token、工程约定、布局/三态/响应式规范与验收清单。

---

### Edge Cases

- 单条消息内容为空或超过 32KB：按参数错误（400）处理。
- 会话标识不存在：发消息/查历史/归档均按资源不存在（404）处理；会话已归档：发消息按参数错误（400）拒绝。
- Provider 不可用（断网、凭据失效）：按 Provider 故障（503）处理。
- Agent 调用超过 60 秒：按超时（504）处理。
- 请求体不是合法 JSON 或 Content-Type 不支持：按参数错误（400）/不支持的媒体类型处理。
- 内部意外故障（如数据库连接异常）：对外只返回统一话术与 500，内部细节只进日志。
- 管理台在 API 全部为空（无会话、无记忆）时打开：各页面显示空态占位而非报错。
- 并发调用（如 200 并发一次性调用）：服务保持稳定响应，不因并发崩溃。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 在统一前缀 `/api/v1` 下暴露按资源分组的 REST 端点，由六个 Controller 承载：会话管理、Agent 调用、Profile 查询、Memory 查询、Tool 查询、系统状态。
- **FR-002**: 会话管理 MUST 提供：创建会话（POST /sessions）、发送消息（POST /sessions/{id}/messages）、查询历史（GET /sessions/{id}）、归档会话（DELETE /sessions/{id}）；创建按 channel+user+profile 三元组幂等；已归档会话 MUST 拒绝新消息（400），历史仍可读。
- **FR-003**: 系统 MUST 提供只读会话列表端点（GET /sessions）供管理台渲染会话列表页（用户已批准，为核心 10 端点之外的第 11 个端点，只读、不承载写操作）。
- **FR-004**: Agent 调用 MUST 提供无状态一次性调用端点（POST /agents/{name}/invoke）：临时建立一次性会话，处理完成即返回最终结果。
- **FR-005**: 信息查询 MUST 提供：Profile 列表（GET /profiles）、长期记忆全量视图（GET /memory）、Tool 列表（GET /tools）。
- **FR-006**: 系统状态 MUST 提供：健康检查（GET /health）与系统信息（GET /info，含版本与各 Provider 连通状态）。
- **FR-007**: Controller MUST 保持薄壳：只做参数校验、响应包装、错误处理；发送消息端点 MUST 调用与 CLI 入口相同的引擎方法（AgentService.process），不得在 Controller 内夹带业务逻辑。
- **FR-008**: 所有异常 MUST 由全局异常处理器统一转成标准错误 JSON（字段 errorCode、message、timestamp）；Controller MUST NOT 自行拼装错误响应。
- **FR-009**: HTTP 状态语义 MUST 固定为：400 参数错误、404 资源不存在、500 内部错误、503 Provider 故障、504 Agent 调用超时；500 兜底响应 MUST NOT 包含内部异常细节（细节只进日志）。
- **FR-010**: 单条消息 MUST 限制最大 32KB，超出按 400 处理；会话历史查询 MUST 最多返回最近 100 条。
- **FR-011**: serve 启动 MUST 只依赖既有约定的一个 provider key 环境变量；MUST NOT 因无关的模型自动装配而索要额外配置项（宪法 II）。
- **FR-012**: 系统 MUST 自动生成 OpenAPI 文档并暴露在 /swagger-ui，无需手写接口文档。
- **FR-013**: 管理平台 MUST 是托管在 /admin 下的静态单页应用：左侧导航五项（会话列表、Profile 列表、Tool 列表、长期记忆、运行状态），分别调用对应 GET 端点渲染；整站只读，MUST NOT 出现任何新建/编辑/删除入口。
- **FR-014**: 管理平台 MUST 在调用出错时显示统一错误 JSON 中的 message；空数据、加载中、错误三态 MUST 有明确占位，不得白屏；SPA 子路由刷新 MUST NOT 返回 404。
- **FR-015**: 管理平台视觉风格 MUST 与项目官网首页完全一致（同一套设计 token：深色底、橙色强调、约定字体与圆角）。
- **FR-016**: 系统 MUST 把管理台视觉与工程约定固化为项目内可复用的生成规范文件（设计 token、工程约定、布局/三态/响应式规范、验收清单），供后续管理台加页复用。

### Key Entities

- **会话（Session）**：一次对话的上下文容器，channel+user+profile 三元组唯一标识；有状态（活跃/归档）与对话历史；Web 与 CLI 入口共享同一份存储。
- **Profile**：一个 Agent 的完整配置；Web 层只读其摘要（名称、描述等），不暴露敏感字段。
- **Tool**：已注册能力项；Web 层只读其名称与描述。
- **长期记忆视图**：当前选定记忆后端的全量只读视图（核心记忆 + 归档窗口）。
- **统一响应/错误信封**：成功响应（code/message/data/timestamp）与错误响应（errorCode/message/timestamp）两种稳定外发格式。
- **Provider 连通状态**：系统信息端点携带的各 Provider 可用性摘要。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 业务系统仅通过 HTTP 即可完成"创建会话 → 发送消息 → 查询历史 → 归档"全链路，每一步返回符合约定格式（验收 harness 全绿 + 真链路 curl 走通）。
- **SC-002**: 任一入口产生的会话对另一入口可见（CLI 聊过的会话可通过查询端点读到），证明两个入口共享同一存储与引擎。
- **SC-003**: 全部 11 个端点的错误响应格式统一（errorCode/message/timestamp 三字段齐全），异常分类命中约定状态码；500 响应中内部异常细节（如连接串）出现率为 0。
- **SC-004**: 超过 32KB 的消息 100% 被拒绝为参数错误；历史查询返回条数不超过 100 条。
- **SC-005**: 仅设置一个 provider key 环境变量即可启动服务，无需任何额外模型配置项。
- **SC-006**: 管理台五个页面在真实数据下正常渲染，整站无任何写操作入口，子路由刷新 0 次 404。
- **SC-007**: 200 并发一次性调用下服务保持稳定（无崩溃、无错误率异常），人工压测确认。
- **SC-008**: /swagger-ui 展示全部端点文档，与实现一致。

## Assumptions

- 部署环境为内网，核心阶段不做认证（扩展阶段补 API Key + JWT）；CORS 开放所有源。
- 会话列表端点（GET /sessions）已经用户批准加入本节范围，为核心 10 端点之外的第 11 个端点，只读。
- 归档与只读列表能力经用户批准以纯加法扩展进 SessionManager 端口（不改既有签名）；会话视图携带状态字段以支撑归档判定。
- 会话列表默认分页口径：按最近活跃倒序，page/size 参数（默认第 0 页、每页 20，上限 100），含归档会话并带状态字段。
- 统一响应信封与全局异常处理地基（成功/错误两种信封、错误码枚举、开放 CORS、OpenAPI 元数据）已在工程地基中就位，本节扩展复用、不另建错误信封。
- 500 兜底话术沿用工程地基既定文案"服务器内部错误"（用户已裁决，不改为课件示意话术）。
- 记忆查询端点返回当前选定后端的全量只读视图，语义与后端裁剪规则保持一致，不引入新的检索参数。
- 前端工程随源码仓托管，构建产物纳入静态资源目录；构建串联方式（绑定进整体构建或独立构建）在 plan 阶段二选一。
- Provider 连通状态以"启动时该 Provider 是否成功注册（凭据齐备）"为口径，不做主动探活。
