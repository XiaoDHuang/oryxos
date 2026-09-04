# Feature Specification: Sandbox 白名单实现（第 24 节）

**Feature Branch**: `024-lesson24-sandbox`

**Created**: 2026-09-04

**Status**: Draft

**Input**: User description: "第24节需求：Sandbox 白名单实现——把已定死的 Sandbox 接口接上核心阶段唯一一档实现，让文件、Shell、HTTP 三类工具调用真正受白名单约束（完整输入见会话记录）"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 白名单内的正常操作放行 (Priority: P1)

企业管理员在 `application.yaml` 配置 `file.allowed_paths` 指向工作区、`shell.allowed_commands` 放行少量只读命令、`http.allowed_domains` 放行内部 API 域名。之后 Agent 在对话中读取工作区文件、执行 `ls`/`cat` 等命令、调用内部接口，全部正常放行，结果回填给模型。

**Why this priority**: 这是本 feature 存在的理由——当前默认 Sandbox 只放行白名单 HTTP、其余动作一律拒绝，文件与 Shell 工具在生产配置下不可用。没有放行路径，白名单实现就只是一堵全封闭的墙。

**Independent Test**: 配置三组白名单后，对每类动作各发一个白名单内请求，验证正常执行且结果返回；不依赖其他 story。

**Acceptance Scenarios**:

1. **Given** `file.allowed_paths` 包含工作区目录，**When** Agent 调用 `read_file` 读取该目录内文件，**Then** 校验通过、文件内容正常返回。
2. **Given** `shell.allowed_commands` 包含 `ls`，**When** Agent 调用 `shell` 执行 `ls -la`，**Then** 校验通过、命令正常执行。
3. **Given** `http.allowed_domains` 包含某内部域名，**When** Agent 调用 `http_get` 请求该域名，**Then** 校验通过、请求正常发出。

---

### User Story 2 - 越界操作在副作用发生前被拦 (Priority: P1)

模型生成越界请求：读取 `..` 爬出白名单目录的文件、执行白名单外命令（如 `rm`）、请求形似白名单域名（如 `evil-example.com` 伪装 `example.com`）。系统在**任何真实 IO 发生之前**拦截，抛出的拒绝原因人可读且包含被拒绝目标；该次调用按普通工具失败落入审计（`tool_invocations` 的 `success=false`），模型下一轮能看到失败原因。

**Why this priority**: 与 US1 同为 P1——白名单的价值一半在放行、一半在拦截，拦截必须证明"危险动作真的没跑"，只断言抛异常不够。

**Independent Test**: 对每类动作各发一个白名单外请求，验证抛出拒绝异常、且底层文件系统/进程/HTTP 调用从未发生；审计路径复用既有失败路径，无需新增代码。

**Acceptance Scenarios**:

1. **Given** 白名单只有工作区目录，**When** Agent 请求读取 `工作区/../../outside/secret.txt` 这类相对路径穿越目标，**Then** 路径标准化后被判定在白名单外、拒绝且文件读取未发生。
2. **Given** `shell.allowed_commands` 不含 `rm`，**When** Agent 执行 `rm -rf x`（含前导空格等变体），**Then** 首 token 比对失败、拒绝且进程未启动。
3. **Given** `http.allowed_domains` 精确列有 `example.com`，**When** Agent 请求 `https://evil-example.com/x`，**Then** 形似域名不命中、拒绝且请求未发出。
4. **Given** 任一拦截发生，**Then** 该次工具调用按既有失败审计路径记录为 `success=false`，错误信息为人可读的拒绝原因。

---

### User Story 3 - 配置语义明确且默认安全 (Priority: P2)

管理员查看配置说明时能理解：任一白名单配置项为空 = 该类动作**全部拒绝**（而非不校验）；默认配置只放行最小集合（工作区目录、四条只读命令、一个天气 API 域名）。同时安全校验接口保持中立——未来升级为容器/microVM 隔离时，接口签名与四个工具的调用代码不变。

**Why this priority**: 语义误解（"空=不校验"）会把安全防线变成摆设，必须写清楚；但它是文档与默认配置层面的工作，不阻塞 US1/US2 的功能交付。

**Independent Test**: 以空配置启动，验证三类动作全部被拒绝；检查配置说明文档包含"空=全拒绝"语义。

**Acceptance Scenarios**:

1. **Given** 某类白名单配置为空列表，**When** Agent 发起该类任何请求，**Then** 一律拒绝。
2. **Given** 配置中出现非法项（如无法规范化的域名），**When** 应用启动加载配置，**Then** 明确报错而非静默放行或静默丢弃该项。

---

### Edge Cases

- 相对路径与 `..` 序列组合（`/workspace/../../outside`）必须先标准化再比对，穿越出白名单根的一律拒绝。
- Shell 命令带前导/连续空格：取 trim 后第一个空白分隔 token 比对；大小写变体按配置原样精确比对（命令名大小写敏感，与文件系统语义一致）。
- HTTP 地址的非 http(s) 协议、缺失 host、带凭证（userinfo）、带 fragment、IP 字面量等形态：沿用既有严格规范化语义，不合规一律拒绝。
- 白名单路径前缀歧义：`/workspace` 不应放行 `/workspace-evil`（路径按目录边界比较，非字符串前缀）。
- 空 target、null 动作等非法输入在值对象边界即被拒绝，不进入校验逻辑。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 提供按动作类型路由的白名单校验实现，覆盖三类既有动作：文件访问（FILE_ACCESS）、Shell 执行（SHELL_EXEC）、HTTP 请求（HTTP_REQUEST）。
- **FR-002**: 文件路径校验 MUST 先做路径标准化与绝对化，再与白名单根按目录边界比较；标准化后落在白名单根之外的目标（含 `..` 穿越）MUST 拒绝。
- **FR-003**: Shell 命令校验 MUST 取命令 trim 后的首 token 与白名单精确比对，不在白名单内 MUST 拒绝。
- **FR-004**: HTTP 校验 MUST 解析目标地址的 host 并与域名白名单**精确比对**；形似域名（以白名单域名结尾但并非该域名的目标，如 `evil-example.com`）MUST NOT 命中。
- **FR-011**: HTTP 域名匹配语法确定为**精确匹配**（2026-09-04 用户决议）：白名单实现 MUST 委托 007 已验收的严格 HTTP 校验组件完成 HTTP_REQUEST 校验，MUST NOT 引入 `*.` 通配符语法，MUST NOT 改动该组件的既有安全语义；课件 harness 的通配符用例以"精确匹配 + 形似域名拒绝"语义等价落地。
- **FR-005**: 三类白名单 MUST 分别由既有配置键 `file.allowed_paths`、`shell.allowed_commands`、`http.allowed_domains` 供给，不新增配置键。
- **FR-006**: 任一白名单配置为空时，该类动作 MUST 全部拒绝；配置说明文档 MUST 明确写出该语义。
- **FR-007**: 校验失败 MUST 抛出既有 `SandboxViolationException`，消息为人可读中文且包含被拒绝的目标；失败 MUST 复用 ToolExecutor 既有审计路径落入 `tool_invocations`（`success=false`、错误信息为拒绝原因），MUST NOT 为 Sandbox 新增审计逻辑。
- **FR-008**: 校验 MUST 先于任何真实 IO：白名单外输入被拦时，文件读写、进程启动、HTTP 请求 MUST NOT 发生；接线回归测试 MUST 断言副作用未发生而非仅断言抛异常。
- **FR-009**: 默认 Sandbox 装配 MUST 从"仅 HTTP 白名单、其余动作拒绝"升级为三类全路由的白名单实现；HTTP 校验 MUST NOT 弱化 007 已验收的严格 host 规范化语义（协议限定、无凭证、无 fragment、IDN/IP 规范化）。
- **FR-010**: `Sandbox` 接口、`SandboxAction`、`ActionType` 三值（`FILE_ACCESS`/`SHELL_EXEC`/`HTTP_REQUEST`）及四个内置 Tool（`FileTools`/`ShellTools`/`HttpTools`/`NotifyTools`）的 enforce 调用点 MUST 保持不变；本节 MUST NOT 修改这些已定字面量。
- **FR-011**: HTTP 域名匹配语法：[NEEDS CLARIFICATION: 课件 harness 要求支持 `*.example.com` 通配符（命中子域、不命中形似域），而 007 已验收的 HTTP 白名单实现是严格精确匹配且拒绝含 `*` 的配置——本节引入通配符需要改动 007 已验收安全组件，还是保持精确匹配并改写 harness 通配符用例？]

### Key Entities

- **安全动作（SandboxAction）**：一次即将产生副作用的意图，由动作类型与目标字符串组成；不携带任何校验实现细节（既有契约，本节不变）。
- **白名单配置**：三组字符串列表（文件路径根、Shell 命令首 token、HTTP 域名），来自 `application.yaml` 既有配置键；空列表语义为"该类全拒绝"。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 白名单内的文件、命令、HTTP 请求在配置正确时 100% 放行并正常返回结果（三类各至少一条自动化用例）。
- **SC-002**: 白名单外请求 100% 在任何真实副作用发生前被拦截，且测试能证明底层 IO 未发生（三类加通知渠道各至少一条自动化用例）。
- **SC-003**: 每次拦截产生一条人可读的拒绝原因，并按既有路径留下 `success=false` 的失败审计记录。
- **SC-004**: 安全校验接口与四个工具调用点的既有签名零改动；升级白名单实现不引起调用方代码变化。
- **SC-005**: 全量测试套件（含前序各节既有测试）全绿，无删除断言、无禁用测试。

## Assumptions

- 三个白名单配置键（`file.allowed_paths`、`shell.allowed_commands`、`http.allowed_domains`）已在 `application.yaml` 存在并带默认值，本节不新增配置键。
- 四个内置 Tool 的 `sandbox.enforce(...)` 调用点已在前序节接线完成，本节只做回归验证与装配升级，不改调用点。
- 007 交付的 HTTP 严格校验组件与 boot 组合接线（Mem0 目标经同一 Sandbox 接口校验）继续有效；本节升级不得破坏该接线。
- 审计失败路径由既有 ToolExecutor 提供，本节零新增审计代码。
- 容器/microVM 档实现、Profile 级 Tool Policy、MCP 与代码执行路径的校验点均属扩展阶段，不在本 feature 范围。
