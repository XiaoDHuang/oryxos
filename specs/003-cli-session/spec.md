# Feature Specification: CLI 命令行入口与会话持久化

**Feature Branch**: `018-lesson18-cli`

**Created**: 2026-08-23

**Status**: Draft

**Input**: User description: "第18节需求:CLI 命令行入口——OryxOS 的本地交互门面与会话持久化地基。CLI 是消息进出的门;命令按轻重分流;会话按三元组幂等持久化,id 拼接只此一处。"

## Clarifications

(暂无)

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 会话按三元组幂等持久化 (Priority: P1)

用户从任意入口发起对话,系统按 channel+user+profile 三元组找到(或新建)唯一会话;同一三元组再来多少次都是同一个会话,任一维度不同就是另一个会话。多轮对话历史整体序列化存储,进程重启后重新打开,历史完整可读。

**Why this priority**: 会话层是后续所有入口共用的地基;"同一个人两条互不相认的历史"这种口径问题出了最难查,必须在入口落地前钉死幂等与隔离。

**Independent Test**: 同三元组两次 getOrCreate 断言同一个会话标识;改任一维度断言不同;写入含多轮消息的会话、换全新数据访问上下文重查,断言历史逐条完整。

**Acceptance Scenarios**:

1. **Given** 通道 cli、用户 wang、Profile default,**When** 连续两次 getOrCreate,**Then** 两次返回的会话标识相同(幂等)。
2. **Given** 同上,**When** 把通道换成 web 再 getOrCreate,**Then** 得到不同会话标识(隔离)。
3. **Given** 已持久化含用户消息、模型响应、工具结果的会话,**When** 进程"重启"(全新上下文)后按标识取回,**Then** 历史消息完整、顺序不变。

---

### User Story 2 - chat 交互式对话入口 (Priority: P2)

用户在终端输入 `oryxos chat [--profile xxx]` 进入交互:每敲一句话,系统把这句话连同当前会话交给引擎,打印引擎的最终回复,直到输入 `/quit` 退出。CLI 自己不做任何 Agent 逻辑。

**Why this priority**: 这是 Provider+ReAct 之后第一个"看得见摸得着"的入口,撑起 Demo 一的完整体验。

**Independent Test**(人工,课件明确不自动化):启动 chat,完成一次多轮对话,/quit 正常退出。

**Acceptance Scenarios**:

1. **Given** 已初始化工程且配好模型凭证,**When** 用户输入一句话,**Then** 引擎处理后打印最终回复,会话历史累积。
2. **Given** 交互中,**When** 输入 `/quit`,**Then** 循环结束、命令退出。

---

### User Story 3 - 12 命令与轻重分流 (Priority: P2)

用户通过单一入口使用 12 个子命令;`init`、`profile list` 这类不需要模型的命令秒回(不启动运行时上下文),`chat`、`serve`、`gateway` 才启动完整运行时,且启动后数据访问层正确装配(仓储扫描到非零个接口)。

**Why this priority**: 启动速度是 CLI 的体感质量;扫描范围是重命令的真实地雷,不显式声明会在启动期炸。

**Independent Test**(人工,课件明确不自动化):`profile list` 秒回;`chat` 启动日志中仓储接口数大于 0;12 个命令 `--help` 正常。

**Acceptance Scenarios**:

1. **Given** 任意工程目录,**When** 执行轻命令,**Then** 不启动运行时即返回结果。
2. **Given** 配好凭证,**When** 执行重命令,**Then** 运行时启动且数据访问层装配正确。

---

### Edge Cases

- 同一 sessionId 并发写:核心阶段单进程内顺序处理,不引入并发控制(扩展阶段再议)。
- 会话历史超长:序列化整存整取,截断策略由引擎侧(prompt 组装)负责,存储层不截。
- 未初始化工作区就执行命令:给出清晰报错提示先 `init`,不抛栈。
- 未知 Profile 名的 chat:启动交互前给出"未注册"报错(沿用 16/17 节口径)。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 单一程序入口 MUST 挂载 12 个子命令(init、status、chat、serve、gateway、profile list/create/show/delete、provider list、tool list、session list),每个子命令一个命令类;参数解析/帮助/报错 MUST 交给命令行框架, MUST NOT 自写 args 解析。
- **FR-002**: 命令 MUST 按轻重分流:不调模型的命令 MUST NOT 启动运行时上下文(直接文件操作);跑引擎的命令才启动,且启动类 MUST 显式声明 JPA 仓储与实体的扫描包。
- **FR-003**: `chat` MUST 实现读输入 → 交引擎 → 打印回复的薄壳交互,`/quit` 退出;MUST NOT 内含拼 prompt、调模型、执行工具等任何 Agent 逻辑。
- **FR-004**: chat/serve/gateway 三种模式 MUST 共享同一份 Profile 配置与同一套会话存储。
- **FR-005**: 会话 MUST 按 channel+user+profile 三元组唯一确定;会话标识的拼接 MUST 只发生在会话管理器内部,入口只提供三元组。
- **FR-006**: 会话管理器 MUST 对外提供 getOrCreate(channel, user, profileName)(幂等)、get(sessionId)、save(session) 三个方法;对话历史 MUST 整体序列化为 JSON 存单一列;表结构 MUST 由手工脚本维护。
- **FR-007**: 会话历史回读 MUST 完整还原消息角色与内容(用户/模型/工具结果),模型响应携带的工具调用意图不丢失。

### Key Entities

- **会话(Session)**:一次对话的上下文容器;持久化形态含会话标识(三元组拼接,主键)、Profile 名、通道、用户标识、消息历史 JSON、状态(active/archived)、创建/最后活跃/归档时间戳。
- **消息历史条目**:每条含角色(user/assistant/tool)、内容、工具调用意图(assistant 携带时)。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 同一三元组 getOrCreate 幂等率 100%;任一维度不同必然产生不同会话——自动化回归钉死。
- **SC-002**: 持久化→"重启"→回读链路消息零丢失,自动化回归钉死。
- **SC-003**: 轻命令本地执行亚秒级返回(不启动运行时);重命令启动日志仓储接口数 > 0。
- **SC-004**: 12 个子命令全部可执行且 `--help` 正常(人工清单)。
- **SC-005**: 自动化验收套件不碰真实网络与密钥,秒级全绿。

## Assumptions

- 第 16 节(Provider/Profile 体系)、第 17 节(AgentService、SessionManager 接口、Session 内存版)交付物已就位并通过验收。
- 命令行框架用 Picocli(已锁定);序列化用 Jackson;存储为 SQLite + 手工建表脚本。
- gateway 本节仅守护进程骨架(核心阶段只有 CLI 一个通道);serve 仅启动 HTTP 运行时,REST 端点属 26 节。
- 命令分流与 `--help` 属进程级行为,自动化成本大于收益,留人工验收清单。
