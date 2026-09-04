---

description: "Task list for feature implementation"
---

# Tasks: Sandbox 白名单实现（第 24 节）

**Input**: Design documents from `/specs/008-sandbox-whitelist/`

**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/ ✅

**Tests**: 宪法要求核心特性必须有独立自动化验收；本 feature 的测试即课件"验收 harness"本体，测试任务先于对应实现任务。

**Organization**: 按 spec.md 三条 user story 分组，每条可独立验证。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 不同文件、无未完成依赖，可并行
- **[Story]**: US1/US2/US3 对应 spec.md 的 user story

## Path Conventions

- 主代码：`oryxos-tool/src/main/java/com/oryxos/tool/`
- 测试：`oryxos-tool/src/test/java/com/oryxos/tool/`
- 配置：`oryxos-boot/src/main/resources/application.yaml`

---

## Phase 1: Setup (Shared Infrastructure)

无——feature 分支 `024-lesson24-sandbox` 与 `specs/008-sandbox-whitelist/` 已由 specify/plan 流程创建；无新模块、无新依赖。

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 三个配置 Properties record 是 WhitelistSandbox 的构造入参，必须先就位

**⚠️ CRITICAL**: T001–T003 完成前不得开始任何 user story

- [x] T001 [P] 创建 `FileSandboxProperties` record（`@ConfigurationProperties("file")`，字段 `List<String> allowedPaths`，中文 Javadoc 只写"为什么"）于 `oryxos-tool/src/main/java/com/oryxos/tool/sandbox/FileSandboxProperties.java`
- [x] T002 [P] 创建 `ShellSandboxProperties` record（`@ConfigurationProperties("shell")`，字段 `List<String> allowedCommands`）于 `oryxos-tool/src/main/java/com/oryxos/tool/sandbox/ShellSandboxProperties.java`
- [x] T003 [P] 创建 `HttpSandboxProperties` record（`@ConfigurationProperties("http")`，字段 `List<String> allowedDomains`）于 `oryxos-tool/src/main/java/com/oryxos/tool/sandbox/HttpSandboxProperties.java`

**Checkpoint**: 三个 record 编译通过（`mvn -pl oryxos-tool compile`）

---

## Phase 3: User Story 1 - 白名单内的正常操作放行 (Priority: P1) 🎯 MVP

**Goal**: `WhitelistSandbox` 实现三类路由校验，白名单内目标放行；成为默认 Sandbox bean

**Independent Test**: `mvn -pl oryxos-tool test -Dtest=WhitelistSandboxTest` 放行用例全绿；`ToolConfigurationTest` 确认默认 bean 类型升级

### Tests for User Story 1 ⚠️（先写，确认 FAIL 后再实现）

- [x] T004 [US1] 创建 `WhitelistSandboxTest` 于 `oryxos-tool/src/test/java/com/oryxos/tool/sandbox/WhitelistSandboxTest.java`，一次性写全课件 harness 用例：文件/Shell/HTTP 三类"允许+拒绝"成对；`..` 相对路径穿越被拦（@DisplayName 保留课件原文"相对路径穿越必须被拦"）；形似域名 `evil-example.com` 不命中精确白名单（@DisplayName"通配符域名_不能被形似域名绕过"，按 FR-011 决议以精确匹配语义等价落地）；Shell 首 token 前导空格变体；空配置=全拒绝。ActionType 用既有三值（FILE_ACCESS/SHELL_EXEC/HTTP_REQUEST），测试方法名英文

### Implementation for User Story 1

- [x] T005 [US1] 实现 `WhitelistSandbox` 于 `oryxos-tool/src/main/java/com/oryxos/tool/sandbox/WhitelistSandbox.java`：构造器消费三个 Properties（null 列表按空处理；allowedRoots 逐项 `Path.of→normalize→toAbsolutePath`，非法项抛 `IllegalArgumentException` 启动失败；内部由 `allowedDomains` 构造 `HttpWhitelistSandbox` 委托实例）；`enforce` 用传统冒号 switch 路由；`checkFilePath`/`checkShellCommand` 为 private，拒绝消息简体中文含目标；HTTP_REQUEST 原样委托；T004 转绿
- [x] T006 [US1] 升级 `oryxos-tool/src/main/java/com/oryxos/tool/ToolConfiguration.java`：类上加 `@EnableConfigurationProperties({FileSandboxProperties.class, ShellSandboxProperties.class, HttpSandboxProperties.class})`；`sandbox(...)` bean 方法改为注入三个 Properties 并返回 `WhitelistSandbox`；删除手写 `Binder` 绑定 `http.allowed-domains` 与不再使用的 import；保留 `@ConditionalOnMissingBean(Sandbox.class)`
- [x] T007 [US1] 更新 `oryxos-tool/src/test/java/com/oryxos/tool/ToolConfigurationTest.java` 默认装配断言：默认 bean 类型 `HttpWhitelistSandbox`→`WhitelistSandbox`；"FILE_ACCESS 默认拒绝"断言改为"空 file 白名单下 FILE_ACCESS 拒绝、配置放行后放行"；保留"用户 Sandbox 优先"与"默认拒绝不依赖测试 Sandbox"语义。依据：第 20 节交付物注明"实现本体 24 节"，行为升级属本节正当范围

**Checkpoint**: `mvn -pl oryxos-tool -am test` 全绿——US1 独立可交付（默认配置下 `.oryxos` 内文件、ls/cat/pwd/echo、白名单域名可用）

---

## Phase 4: User Story 2 - 越界操作在副作用发生前被拦 (Priority: P1)

**Goal**: 证明白名单外输入被拦时真实 IO 未发生；失败审计复用既有路径

**Independent Test**: 四个 Tool 各一条接线回归（mock/伪造底层执行器，`verify(..., never())` 或文件系统状态断言）

### Tests for User Story 2 ⚠️（本 phase 即测试本体；Tool 调用点已存在，测试写完应直接全绿——若红说明实现有洞，当场修实现）

- [x] T008 [P] [US2] `FileToolsTest` 追加接线回归于 `oryxos-tool/src/test/java/com/oryxos/tool/builtin/FileToolsTest.java`：以仅放行临时目录的 `WhitelistSandbox` 构造 `FileTools`，对目录外目标调 `write_file`/`read_file`，断言抛 `SandboxViolationException`（或既有失败返回语义）且目标文件未创建、目录外文件内容未变
- [x] T009 [P] [US2] `ShellToolsTest` 追加接线回归于 `oryxos-tool/src/test/java/com/oryxos/tool/builtin/ShellToolsTest.java`：用既有包私有构造器注入伪造 `starter` + 仅放行 `ls` 的 `WhitelistSandbox`，执行白名单外命令，断言拒绝且 `starter` 从未被调用（进程未启动）
- [x] T010 [P] [US2] `HttpToolsTest` 追加接线回归于 `oryxos-tool/src/test/java/com/oryxos/tool/builtin/HttpToolsTest.java`：精确白名单外的 URL（含形似域名 `evil-example.com`）被拦，断言底层传输零调用
- [x] T011 [P] [US2] `NotifyToolsTest` 追加接线回归于 `oryxos-tool/src/test/java/com/oryxos/tool/builtin/NotifyToolsTest.java`：复用既有 ProfileContext fixture 配置白名单外 webhook URL，断言拒绝且伪造 `NotifyChannelAdapter.send` 从未被调用

### Implementation for User Story 2

- [x] T012 [US2] 核验 `ToolExecutor` 既有失败审计覆盖 Sandbox 拒绝路径：`SandboxViolationException` 从 Tool 抛出后落 `tool_invocations` 的 `success=false` 且 `error_message` 含拒绝原因——先读 `oryxos-core` 既有 ToolExecutor 测试确认覆盖；确有缺口才在 `oryxos-core/src/test/java/com/oryxos/core/react/` 补一条（不新增生产代码）

**Checkpoint**: `mvn -pl oryxos-tool -am test` 全绿；四条接线回归证明"IO 未发生"

---

## Phase 5: User Story 3 - 配置语义明确且默认安全 (Priority: P2)

**Goal**: "空=全拒绝"与"非法项启动失败"语义固化进测试与文档

**Independent Test**: 空配置与非法配置用例全绿；配置说明可被人读懂

### Tests for User Story 3

- [x] T013 [P] [US3] `ToolConfigurationTest` 追加空配置用例于 `oryxos-tool/src/test/java/com/oryxos/tool/ToolConfigurationTest.java`：三个键均缺省时默认 `WhitelistSandbox` 对 FILE_ACCESS/SHELL_EXEC/HTTP_REQUEST 全拒绝
- [x] T014 [P] [US3] `WhitelistSandboxTest` 追加非法配置用例（同一文件内）：非法路径项构造即抛 `IllegalArgumentException`；HTTP 非法项沿用 `HttpWhitelistSandbox` 既有"整份配置拒绝"（该行为已由 `HttpWhitelistSandboxTest` 覆盖时仅加委托透传断言，不重复造）

### Implementation for User Story 3

- [x] T015 [US3] `oryxos-boot/src/main/resources/application.yaml` 的 Sandbox 配置段注释补"任一白名单置空 = 该类动作全部拒绝（而非不校验）"；键名与默认值不动
- [x] T016 [P] [US3] 配置说明文档写入"空=全拒绝"边界：定位 website/docs 既有配置说明页（先 grep `allowed_paths` 找落点），补一段空值语义与三类键说明；找不到既有落点时停下报告，不新造文档结构

**Checkpoint**: US3 独立验证通过（空配置启动全拒绝、文档语义明确）

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 全量门禁与收尾证据

- [x] T017 运行 `mvn clean verify`（不跳任何插件：Spotless/P3C/Checkstyle/SpotBugs/FindSecBugs/PMD/OWASP 全绿）；红了当场修实现，不动断言
- [x] T018 前序节回归确认：全量测试含 007 Memory 三后端、20 节 Tool 体系、19 节 Notify、17 节 ReAct 等全部既有测试绿（跨节契约证据）
- [x] T019 执行 `specs/008-sandbox-whitelist/quickstart.md` 自动化项；整理人工验证项清单（真实链路集成验证、接口中立性自查、配置语义抽查）写入验收报告

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 2 (T001–T003)**: 阻塞一切——WhitelistSandbox 构造入参
- **Phase 3 (US1)**: 依赖 Phase 2；T004 先于 T005（先红后绿）；T006/T007 在 T005 后
- **Phase 4 (US2)**: 依赖 T005（WhitelistSandbox 类存在）；T008–T011 互不依赖可并行
- **Phase 5 (US3)**: 依赖 T005/T006；T013–T016 可并行
- **Phase 6**: 依赖全部 story 完成

### User Story Dependencies

- **US1 (P1)**: 仅依赖 Foundational
- **US2 (P1)**: 逻辑依赖 US1 的实现类，但测试断言独立（直接构造实例，不经 Spring）
- **US3 (P2)**: 依赖 US1 装配升级；文档任务与代码解耦

### Parallel Opportunities

- T001/T002/T003 三个 record 并行
- T008/T009/T010/T011 四个 Tool 回归并行（不同文件）
- T013/T014/T015/T016 并行

### Parallel Example: User Story 2

```bash
# 四个 Tool 接线回归同时展开（不同测试文件）：
Task: "FileToolsTest 追加拦截回归"
Task: "ShellToolsTest 追加拦截回归（伪造 starter）"
Task: "HttpToolsTest 追加拦截回归（形似域名）"
Task: "NotifyToolsTest 追加拦截回归（伪造 adapter）"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. T001–T003 Properties record → 编译过
2. T004 测试先红 → T005 实现转绿 → T006/T007 装配升级
3. **STOP**：`WhitelistSandboxTest` + `ToolConfigurationTest` 绿即 MVP 成立

### Incremental Delivery

1. Foundational → US1（白名单生效）→ US2（拦截证据）→ US3（语义与文档）→ Polish
2. 每个 phase 结束跑 `mvn -pl oryxos-tool -am test`，不攒红

---

## Notes

- 接口五件（Sandbox/SandboxAction/ActionType/SandboxViolationException/HttpWhitelistSandbox）为前序节交付物，本节只读不改
- 课件 ActionType 四值代码块不采用：既有三值为已定字面量（research.md 决策 4）
- HTTP 通配符不做：FR-011 用户决议精确匹配（research.md 决策 1）
- 语法禁区：`enforce` 路由用传统冒号 switch（research.md 决策 7）
- 全程不 commit/push，同步时机由用户决定
