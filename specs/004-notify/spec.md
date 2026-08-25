# Feature Specification: 主动通知出口

**Feature Branch**: `019-lesson19-notify`

**Created**: 2026-08-24

**Status**: Draft

**Input**: User description: "第19节需求：Notify 模块——为无人等待同步响应的定时任务、日报等场景提供统一的主动通知出口。"

## Clarifications

### Session 2026-08-24

- Q: Agent 未指定通知渠道时如何选择目标? → A: 使用 Profile 中第一个通知目标;未配置目标或指定类型不存在时明确报错。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 向配置好的 webhook 主动推送内容 (Priority: P1)

作为运行定时任务或日报 Agent 的企业用户，我希望 Agent 能把结果主动推送到配置好的通知目标，
这样即使没有人在同步请求链路上等待，也能及时收到执行结果。

**Why this priority**: 没有主动出口，定时 Agent 即使完成推理和工具调用，结果也只能留在会话中，
无法产生业务价值。

**Independent Test**: 配置一个本地假 webhook，发送一条内容，验证目标收到一次 POST，消息正文
包含原始内容，且请求地址来自目标配置。

**Acceptance Scenarios**:

1. **Given** 已配置一个可用 webhook 目标，**When** 系统发送通知内容，**Then** 目标收到一次包含该内容的请求。
2. **Given** 配置了两个不同 webhook 目标，**When** 分别向两者发送，**Then** 请求到达各自配置的地址，不使用硬编码地址。

---

### User Story 2 - 通知失败必须对调用方可见 (Priority: P1)

作为运维人员，我希望 webhook 拒绝请求或返回服务端错误时通知操作明确失败，而不是静默显示成功，
这样 Agent 不会误以为消息已经送达。

**Why this priority**: 静默吞错会造成通知丢失且无法追查，比直接失败更危险。

**Independent Test**: 本地假 webhook 返回服务端错误，验证发送操作抛出明确异常且不会伪造成功结果。

**Acceptance Scenarios**:

1. **Given** webhook 返回 5xx，**When** 系统发送通知，**Then** 错误向上传递，调用方能判定通知失败。
2. **Given** 通知配置缺少目标地址，**When** 系统尝试发送，**Then** 在发出网络请求前明确拒绝。

---

### User Story 3 - 用中立契约扩展通知渠道 (Priority: P2)

作为平台维护者，我希望通知调用方只表达“向目标发送内容”，不感知企业微信、飞书等具体渠道，
这样以后新增渠道实现时不需要修改 Agent 或调用方。

**Why this priority**: 渠道差异应该留在出站适配层，避免每个 Agent 重复编写 webhook 细节。

**Independent Test**: 使用两个不同渠道类型的目标替身，验证调用契约和目标数据结构不需要变化。

**Acceptance Scenarios**:

1. **Given** 一个新的通知渠道实现，**When** 它接入通知出口，**Then** 既有调用方无需修改参数或流程。
2. **Given** 目标包含渠道专属配置，**When** 发送通知，**Then** 只有对应渠道实现解释这些配置。

### Edge Cases

- 通知目标未配置或目标地址为空时，在网络调用前明确失败。
- webhook 返回 4xx/5xx 或网络异常时，异常向上传递，不静默吞掉。
- 内容为空时仍按调用方明确传入的内容处理，不擅自生成业务文案。
- webhook 地址属于敏感配置，不能出现在模型参数、代码、日志或版本库明文中。
- 多个通知目标并存时，未指定渠道使用 Profile 中第一个目标；指定渠道类型不存在时明确失败。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 提供不携带具体渠道名称的统一通知发送契约，输入仅包含通知目标和内容。
- **FR-002**: 通知目标 MUST 包含渠道类型和渠道专属配置，专属配置只能由对应渠道实现解释。
- **FR-003**: 核心阶段 MUST 支持通用 HTTP webhook：请求地址来自目标配置，使用 POST 发送包含原始内容的消息体。
- **FR-004**: webhook 返回非成功状态或发生网络错误时，系统 MUST 向上传递失败，MUST NOT 静默吞错或返回伪成功。
- **FR-005**: 通知地址等敏感配置 MUST 通过运行时配置注入，MUST NOT 暴露给模型或明文写入代码、日志和版本库。
- **FR-006**: 通知渠道 MUST 可通过新增实现扩展，既有发送契约和调用方 MUST NOT 因新增渠道而修改。

### Deferred Requirements *(not part of 019 acceptance)*

- **DR-001**: 第 20/24 节在通知能力注册为 Agent 可调用工具前，系统必须解析当前 Agent 的通知目标：未指定渠道时使用第一个目标，指定渠道类型不存在或未配置目标时明确失败；安全校验必须先于实际发送。
- **DR-002**: 第 27/28 节串联验证中，通知调用必须复用统一工具审计链路，不新增独立审计表或旁路审计机制。

### Key Entities

- **通知目标**: 一次通知要送达的抽象目标，包含渠道类型和渠道专属配置。
- **通知内容**: 调用方要求原样发送的文本内容。
- **通知渠道实现**: 解释某类目标配置并执行发送的可替换实现。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 对一个可用的本地 webhook，每次通知产生且只产生一次 POST，请求内容 100% 包含调用方提供的文本。
- **SC-002**: webhook 地址 100% 来自通知目标配置；自动化测试中不存在硬编码生产地址。
- **SC-003**: webhook 返回 5xx 时，100% 的调用向上传递失败，不出现伪成功。
- **SC-004**: 新增第二种通知渠道时，统一发送契约和既有调用方的参数数量、含义保持不变。
- **SC-005**: 自动化验收全部使用本地假服务，不访问真实外网、不依赖真实 webhook 或凭证。
- **SC-006**: Agent 工具接线、安全校验顺序和统一审计的延期工作均有明确后续任务，不在本节伪造不完整实现。

## Assumptions

- 当前 Profile 已能声明一个或多个通知目标及其渠道专属配置。
- 核心阶段只交付通用 webhook 发送；各平台专用 payload、签名、AccessToken、SMTP、短信留到扩展阶段。
- 可独立验证的通知契约、目标模型和 webhook 发送在本节完成。
- Agent 可调用的通知工具依赖后续 Tool 注册和 Sandbox 能力，在对应课程完成后接线和验收。
- 真实 webhook 送达属于人工配置冒烟，不进入默认自动化测试。
