# Feature Specification: 插件化 Agent 目录——一个目录定义一个会自己跑的 Agent

**Feature Branch**: `029-lesson29-agent-directory`

**Created**: 2026-09-09

**Status**: Draft

**Input**: User description: "第29节需求：插件化 Agent 目录——一个目录定义一个会自己跑的 Agent。底座（Provider、ReAct、内置 Tool、Memory、Sandbox、定时、Web Service）已齐备且跑稳，缺的是"定义一个 Agent、把它装上去、让它跑起来"的标准机制。定义一个 Agent 退化为往 agents 目录丢一个自足目录（主文件 frontmatter = 运行配置，正文 = 任务指令；可选参考/子指令/脚本按需取用），系统扫描派生注册后即可到点自动跑，全程不写 Java、不动底座。运行时注册就位（注册表可变、调度器按单配置注册并留句柄），为下一节 API 管理铺路。手写 Profile 与 Agent 目录两条来源并存、过同一套校验。"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 丢一个目录就定义出一个 Agent (Priority: P1)

业务方要新增一个业务 Agent（示例贯穿全节：每日订单对账 Agent——每天核对交易库与清算库昨天订单的条数与金额，有差异按规范生成分级报告推运维群）。他只写一个自足的 Agent 目录（主文件：frontmatter 运行配置 + 正文任务指令；可选 REFERENCE.md 参考、skills/ 子指令、scripts/ 脚本），丢进工作区的 agents 目录。系统启动扫描后，这个 Agent 出现在 Agent 列表（`oryxos profile list` / `GET /api/v1/profiles`）里，全程不写一行 Java、不改动底座。frontmatter 派生出的运行配置与手写 Profile 走完全同一套校验：缺必填项报错点名、引用未注册能力告警。

**Why this priority**: 这是"插件化"的机制本体，是本节存在的理由；没有它，其余故事（自动跑、运行时注册）都没有作用对象。

**Independent Test**: 往 agents 目录放一个合法 Agent 目录、再放一个缺必填项的坏目录，重启后合法 Agent 出现在列表、坏目录被点名报错且不阻断其他 Agent；可由自动化扫描注册测试独立验证。

**Acceptance Scenarios**:

1. **Given** 工作区 agents 目录下有一个字段齐全的 Agent 目录, **When** 系统启动扫描, **Then** 该 Agent 以派生的运行配置注册成功，出现在 Agent 列表中，与手写 Profile 不可区分。
2. **Given** 一个 Agent 目录的主文件缺 `name` 或 `provider` 等必填项, **When** 系统启动扫描, **Then** 加载报错并点名缺失字段与目录，其余 Agent 不受影响。
3. **Given** 一个 Agent 的 frontmatter 引用了底座未注册的能力（tool）, **When** 系统启动扫描, **Then** 加载给出告警指出未注册能力名，不阻断该 Agent 其余部分及其余 Agent 的注册。
4. **Given** 工作区同时存在手写 Profile 与 Agent 目录, **When** 系统启动, **Then** 两条来源都注册成功，且过同一套校验规则（同一校验代码路径）。

---

### User Story 2 - Agent 到点自己跑，目录里的资源按需进上下文 (Priority: P2)

业务方在 Agent 主文件 frontmatter 里声明了定时（cron + 时区 + 触发消息）。系统到点自动触发这个 Agent：正文指令进入 system prompt，模型按正文指引先跑脚本拿确定性数据（只有脚本产出进上下文、代码不进），需要报告规范时才读子指令文件，拿不准某条差异才读参考文件，最后推送并留痕。运营改 Agent 目录里的正文，不重启进程，下一次触发立即用新说明。

**Why this priority**: "会自己跑"是本节标题承诺的终态；渐进式披露（正文常驻、资源按需）是该目录形态的核心设计，二者一起证明 Agent 目录零改动复用了整台底座。

**Independent Test**: 注册一个带定时声明的 Agent，验证定时被既有调度器接受（cron/时区原样来自声明）；触发时断言正文进了 system prompt、参考/子指令/脚本未被预载，只经底座读文件/执行命令能力按需取用。可由自动化测试独立验证，不依赖 US1 的扫描路径（可直接派生注册）。

**Acceptance Scenarios**:

1. **Given** Agent 的 frontmatter 声明了 `schedules`, **When** 派生与注册完成, **Then** 定时声明原样进入派生的运行配置并被调度器注册，cron/时区/触发消息与声明一致。
2. **Given** Agent 已被触发, **When** 组装本轮 prompt, **Then** 主文件正文（去掉 frontmatter）出现在 system prompt 中，且每次触发都从磁盘现读（无缓存）。
3. **Given** Agent 目录里有 REFERENCE.md / skills/*.md / scripts/*, **When** 组装 system prompt, **Then** 这些资源内容不被预载进 prompt；模型只能按正文指引用底座既有的读文件/执行命令能力按需取用，脚本仅产出进上下文。
4. **Given** 运营修改了 Agent 主文件的正文, **When** 下一次触发到来（进程未重启）, **Then** system prompt 使用修改后的新正文。

---

### User Story 3 - 运行时不重启注册/摘除 Agent (Priority: P3)

平台侧需要在进程不重启的情况下注册一个新 Agent（下一节 API 管理的铺路需求）。运行时注册与启动扫描走同一段代码、同一套校验：非法配置在运行时注册时报出的异常类型与消息，和启动路径完全一致。带定时声明的 Agent 运行时注册后，调度器按单个配置完成注册并留下定时句柄，后续可以摘除。

**Why this priority**: 为下一节"API 定义/管理 Agent"提供机制前提；本节只要求机制就位，不要求对外端点。

**Independent Test**: 进程已启动后调用运行时注册：合法配置立即可见；非法配置抛出的异常类型与消息与启动加载路径逐字一致；带定时的配置注册后调度器句柄表出现对应句柄。可全部由自动化测试独立验证。

**Acceptance Scenarios**:

1. **Given** 进程已启动, **When** 运行时注册一个合法的派生配置, **Then** 按名称查询立即可见，与启动扫描注册的不可区分。
2. **Given** 进程已启动, **When** 运行时注册一个非法配置（如 provider 未声明）, **Then** 抛出的异常类型与消息同启动加载路径完全一致。
3. **Given** 运行时注册一个带定时声明的配置, **When** 注册完成, **Then** 调度器按任务的定时句柄表中存在对应句柄，cron/时区来自声明。

---

### Edge Cases

- agents 目录不存在或为空：扫描正常完成、注册数为零，不报错阻断启动。
- Agent 目录缺主文件 `AGENT.md`：该目录被跳过并记错误日志点名，不影响其余目录。
- 主文件 frontmatter 无法解析（坏 YAML/缺 `---` 分隔）：按损坏处理，记错误日志点名并跳过，不阻断其余 Agent。
- 派生 Agent 与既有手写 Profile 同名：本节不做同名冲突策略（课件边界）；默认行为为记错误日志并跳过派生注册，不静默覆盖既有注册项（见 Assumptions）。
- 定时声明非法（缺要素/cron 或时区不可解析/id 重复）：沿用既有调度器规则——记错误日志跳过该条，不阻断 Agent 本身注册。
- 脚本是由解释器执行的任意代码，可自发网络请求、绕过 HTTP 域名白名单：核心阶段只做解释器+目录两道白名单，信任边界=安装带脚本的 Agent 即信任其作者；容器/网络隔离属扩展阶段（如实记录，不在本节实现）。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 在启动时扫描工作区的 agents 目录，对每个 Agent 子目录解析其主文件 `AGENT.md`，拆出 frontmatter（运行配置）与正文（任务指令），并识别 `scripts/`、`skills/`、`REFERENCE.md` 等自有资源的位置。
- **FR-002**: 系统 MUST 把 frontmatter 派生成与手写 Profile 同构的运行配置：name / description / identity / provider / tools / notify_channels / schedules 一一对应，正文与资源目录绑定到该配置；MUST NOT 形成第二套运行时模型或跨 Agent 全局能力索引。
- **FR-003**: 派生配置 MUST 走与手写 Profile 完全同一套校验后注册（同一校验代码路径）；主文件缺必填项（name、provider 等）时加载 MUST 报错并点名缺失字段与来源目录。
- **FR-004**: frontmatter 引用了底座未注册的能力时，系统 MUST 给出告警并指出未注册能力名，MUST NOT 因此阻断其余 Agent 的注册。
- **FR-005**: Agent 正文 MUST 在被触发时进入 system prompt，且每次触发都从磁盘现读（无缓存），使正文修改不重启即在下一次触发生效；注入时 MUST 去掉 frontmatter 只留正文。
- **FR-006**: Agent 目录内的参考、子指令、脚本 MUST NOT 被预载进 prompt；模型只能按正文指引，用底座既有的读文件/执行命令能力按需取用；脚本只有产出进上下文、代码不进。
- **FR-007**: frontmatter 声明的定时 MUST 原样派生进运行配置，由既有调度器照旧注册，到点经与人工触发完全相同的入口自动执行、推送并审计留账；调度器实现本身零改动。
- **FR-008**: 注册表 MUST 从启动期不可变改为可变并发结构，提供运行时注册、按名移除、按名存在性判断三个能力。
- **FR-009**: 运行时注册 MUST 与启动扫描共用同一段校验代码：非法配置在两条路径报出同一异常类型与同一消息。
- **FR-010**: 调度器 MUST 把全量注册的循环体抽成按单个配置注册的入口，并保留按任务的定时句柄表，供后续注销/更新使用。
- **FR-011**: 本节 MUST 产出示例 Agent 目录 `daily-reconcile/`（主文件 + scripts/reconcile.py + skills/report-format.md + REFERENCE.md 四部分俱全），作为手工验证路径的参照物。
- **FR-012**: 手写 Profile YAML 来源 MUST 保持既有行为不回退；两条来源并存、过同一套校验。

### Key Entities *(include if feature involves data)*

- **Agent 目录**：工作区 `agents/<name>/` 下的自足目录，定义一个业务 Agent。主文件 `AGENT.md`（frontmatter = 该 Agent 的运行配置；正文 = 任务指令）必备；`REFERENCE.md`（参考）、`skills/*.md`（子指令）、`scripts/*`（脚本）可选，均"用到才进上下文"。
- **派生运行配置**：由 frontmatter 映射出的运行时配置对象，与手写 Profile 同构同校验，是 Agent 目录进入底座的唯一形态；正文与资源目录绑定于它。
- **定时声明**：frontmatter 中的 schedules 块（id / cron / zone / message），派生后由调度器注册，是"Agent 到点自己跑"的唯一来源。
- **定时句柄**：调度器为每条已注册定时规则保留的进程内句柄，供运行时注销/更新使用。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 业务方定义一个新 Agent 的代码改动量为零行 Java：放一个合法目录后，下次启动 100% 出现在 Agent 列表（harness 扫描注册测试钉死）。
- **SC-002**: 声明了定时的 Agent，其定时 100% 被调度器接受且 cron/时区/触发消息与声明逐字一致；真模型链路到点触发、推送到达、审计有账（人工验收项）。
- **SC-003**: 修改 Agent 正文后下一次触发即生效，所需重启次数为 0（每次触发现读的回归断言钉死）。
- **SC-004**: 运行时注册合法配置后立即可见（0 次重启）；非法配置在运行时与启动两条路径的报错完全一致——同一异常类型、同一消息，一致率 100%（harness 逐字比对钉死）。
- **SC-005**: 本节自动化验收套件（解析、派生、扫描注册、运行时注册、调度注册、渐进式披露六组）全绿，且前序各节全部测试不回退（`mvn clean verify` 全绿）。

## Assumptions

- 手写 Profile YAML（`.oryxos/profiles/`）继续作为受支持来源存在：课件的"profiles/ 取消"指新 Agent 的作者路径收敛为 Agent 目录，而课件验收清单明确要求"两条来源同规矩"，故手写路径保留且不回退。
- 同名冲突不做专门策略（课件边界）：派生 Agent 与既有注册项同名时，默认记错误日志并跳过派生注册，不静默覆盖；专门的冲突策略（覆盖/改名/拒绝）留扩展阶段。
- 正文即时生效的实现语义是"每次触发从磁盘现读主文件正文"，既有无缓存回归（17 节 ContextLoaderTest）已钉死，本节不重复测试该性质本身，只测正文进 prompt 与资源不预载。
- 脚本安全边界为"解释器白名单 + 只能跑本 Agent scripts/ 目录"两道应用层白名单；脚本作为任意代码可自发网络请求，不受 HTTP 域名白名单约束，此信任边界如实记录，容器/网络隔离不在本节。
- 运行时注册只要求机制就位（注册表可变、调度器按单配置注册并留句柄）；对外的 API 管理端点与文件监听热加载是下一节范围，本节不做。
- 工作区 agents 目录不存在视为合法空态（注册数为零），与 profiles 目录缺失的既有处理风格一致。
