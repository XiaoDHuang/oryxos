# Feature Specification: ReAct 循环(Agent 大脑循环)

**Feature Branch**: `017-lesson17-react-loop`

**Created**: 2026-08-23

**Status**: Draft

**Input**: User description: "第17节需求:ReAct 循环——Agent 的大脑循环,让模型'想一步、做一步、看结果'直到把事办成。循环自己写、不用框架黑盒;最大轮数兜底、历史截断、逐轮累积、成败审计、ProfileContext 请求级上下文。"

## Clarifications

(暂无)

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 多轮"想—做—看"直到办成 (Priority: P1)

用户抛出一个一句话答不了的任务(如"看看今天天气,帮我决定穿什么")。系统进入循环:模型想下一步、请求调工具、系统执行工具并把结果回填、模型看结果继续想,直到模型不再要求调工具,输出最终答复。

**Why this priority**: 这是 ReAct 的本体,没有它系统退化为 chatbot;后续所有能力(CLI、Web、定时、Demo)都跑在这个循环上。

**Independent Test**: mock 大模型第一轮返回"想调天气工具"、第二轮返回最终建议,验证:工具被执行一次、结果被回填进会话、最终答复返回、循环恰好两轮结束。

**Acceptance Scenarios**:

1. **Given** 模型首轮响应携带工具调用意图,**When** 循环执行,**Then** 该工具被顺序执行一次且结果回填进会话历史,循环进入下一轮。
2. **Given** 模型某轮响应不携带任何工具调用意图,**When** 循环判断,**Then** 立即返回该轮文本作为最终答复,不再调模型。

---

### User Story 2 - 死循环兜底与上下文不撑爆 (Priority: P1)

模型异常地每轮都要求调工具、永不收敛。系统在配置的最大轮数(默认 10)处强制停止并返回明确收尾语,调用次数一轮不多;同时无论转多少轮,发给模型的会话历史始终只含最近 N 轮(默认 20),上下文不随轮数无限膨胀。

**Why this priority**: 死循环烧钱、上下文撑爆是 ReAct 最经典的两个生产事故,必须在设计里定死,不是事后补。

**Independent Test**: 让 mock 模型每轮都要求调工具,验证调用次数恰好等于最大轮数、返回文案含"达到最大轮数";构造超 N 轮历史的会话,验证组装出的 prompt 只含最近 N 轮。

**Acceptance Scenarios**:

1. **Given** 模型每轮都要求调工具,**When** 循环跑到最大轮数,**Then** 强制停止,模型被调用次数恰好等于上限,返回明确收尾语。
2. **Given** 会话历史超过 N 轮,**When** 组装 prompt,**Then** 仅保留最近 N 轮,更早的轮次被截断。
3. **Given** 任意一次组装,**When** 检查 system prompt,**Then** 末尾附当前日期时间。

---

### User Story 3 - 全程留痕可审计 (Priority: P2)

一次处理结束后,审计方能在会话记录里看到每一轮模型的响应与每次工具调用结果;工具调用无论成败,审计表都有一条记录(含成功标识与失败原因)。

**Why this priority**: "可审计"是产品差异化卖点;失败调用不留痕等于事故隐形。

**Independent Test**: 制造一次成功与一次失败的工具调用,验证审计存储各多一条记录,失败记录带原因;处理结束后会话持久化被调用。

**Acceptance Scenarios**:

1. **Given** 一次成功的工具调用,**When** 执行完成,**Then** 审计记录含成功标识=true。
2. **Given** 一次抛错的工具调用,**When** 执行失败,**Then** 审计记录含成功标识=false 与失败原因,且错误信息回填进会话供模型下一轮看到。

---

### User Story 4 - 请求级 Profile 上下文不串号 (Priority: P2)

多个 Agent 共用同一实例。一次处理开始时,系统把当前 Agent 的 Profile 放进请求级上下文,供工具执行期读取(例如通知工具要读当前 Agent 的通知渠道);处理结束——包括处理中抛异常——上下文必须被清除,防止虚拟线程复用时下一个请求读到别人的 Profile。

**Why this priority**: ThreadLocal 泄漏在单请求下永远不报错,只在并发复用时串号,是最阴险的一类 bug,必须 harness 显式钉死。

**Independent Test**: 处理中让循环抛异常,验证异常上抛后请求级上下文为空;正常处理流程中工具能取到当前 Profile。

**Acceptance Scenarios**:

1. **Given** 一次正常处理,**When** 工具执行期间读取请求级上下文,**Then** 取到的是当前会话对应的 Profile。
2. **Given** 处理中循环抛异常,**When** 异常向上抛,**Then** 请求级上下文已被清空。

---

### Edge Cases

- 工具执行抛异常:不吞、不留空白——审计落失败记录,错误信息包装成工具结果回填会话,循环继续(模型下轮能看到失败)。
- Profile 引用不存在的工具名:执行器按"未知工具"失败路径处理(审计 success=false),不让循环崩溃。
- Bootstrap/Skill 文件缺失:Profile 显式引用的文件缺失报错;Bootstrap 缺失至少 WARN;绝不静默跳过("人格悄悄丢了"是最难查的软故障)。
- 上下文文件被用户中途修改:下一次组装立即读到新内容(不缓存)。
- Profile 未配置轮数/历史上限:按默认值 10 / 20。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 实现自主控制的循环调度:用户消息入会话 → 组装 prompt → 调模型 → 无工具调用意图则返回最终答复;有则顺序执行工具并回填,进入下一轮。MUST NOT 使用框架自带的 Agent/循环封装。
- **FR-002**: 循环迭代次数 MUST 受 Profile 配置的最大轮数约束(默认 10),转满 MUST 强制停止并返回明确收尾语。
- **FR-003**: 组装 prompt 时 MUST 只保留最近 N 轮会话历史(默认 20,Profile 可配),超出截断;system prompt 末尾 MUST 附当前日期时间。
- **FR-004**: prompt 组装顺序 MUST 固定为:system prompt(角色设定+Bootstrap+Skill)→ 长期记忆(本节留接入位)→ 截断后的会话历史 → 当前可用工具列表。上下文文件 MUST 每次组装重新读取, MUST NOT 缓存;Profile 显式引用的文件缺失 MUST 报错,Bootstrap 缺失 MUST 至少 WARN。
- **FR-005**: 每轮模型响应与工具结果 MUST 累积进会话;处理结束后会话 MUST 被持久化。
- **FR-006**: 工具执行 MUST 收敛于唯一执行器;成功与失败 MUST 各写一条审计记录(含 success/error_message,与 llm_calls 同口径);工具失败时错误信息 MUST 回填会话,循环不崩溃。
- **FR-007**: 统一处理入口 MUST 在处理开始时将当前 Profile 放入请求级上下文(ThreadLocal),结束时(含异常路径)在 finally 中清除。
- **FR-008**: 一次响应携带多个工具调用时 MUST 按顺序逐个执行, MUST NOT 并行。
- **FR-009**: 本节 Session 为内存版;其 SQLite 持久化、SessionManager 完整实现、CLI 接入属第 18 节。

### Key Entities

- **Session(会话)**:一次对话的上下文容器,含标识、关联 Profile 名、逐轮消息列表(用户消息、模型响应、工具结果);本节内存版,18 节升级持久化。
- **工具调用请求**:模型响应中携带的"想调某工具、参数是什么"的意图,由执行器兑现。
- **工具结果**:执行器输出,含成功标识、内容、错误信息;回填会话并落审计。
- **工具调用审计记录**:一次工具执行的留痕,关联会话与 Profile,含参数、成功标识、失败原因、起止时间。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 模型不收敛时,模型被调用次数恰好等于最大轮数(默认 10),零超调;自动化回归测试钉死。
- **SC-002**: 任意轮数下,发给模型的历史轮数不超过 N(默认 20);截断行为有自动化回归测试。
- **SC-003**: 工具调用审计留痕率 100%(成败各半的混合场景下,审计条数 = 调用次数,失败记录 100% 带原因)。
- **SC-004**: 并发复用场景零串号——异常路径下请求级上下文清空有自动化回归测试。
- **SC-005**: 核心验收测试套件不依赖真实网络与真实密钥,本地秒级全绿。
- **SC-006**: 换模型/换工具只改 Profile,循环与编排代码零改动。

## Assumptions

- 第 16 节交付物(ProviderService、Profile/ProfileRegistry、Prompt、OryxTool 最小抽象、llm_calls 审计口径)已就位并通过验收。
- Session/SessionManager(18 节)、ToolRegistry/ToolResult(20 节)、Sandbox(24 节)是后续节交付物;本节需要的最小前向形状将逐项报用户确认后落地。
- 长期记忆(Memory)由 22 节交付,本节 prompt 组装只留接入位。
- 沙箱检查的具体实现由 23/24 节交付,本节在执行器中预留调用位并注明接线点。
