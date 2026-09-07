---
description: "Task list for 010 Web Service 与第一版管理平台"
---

# Tasks: Web Service 与第一版管理平台（010）

**Input**: Design documents from `/specs/010-web-service-admin/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/api-v1.md

**Tests**: 课件验收 harness 必备——`SessionApiControllerTest`、`GlobalExceptionHandlerTest`、`WebSmokeIT` 三类为硬门禁；测试任务先于对应实现任务（先红后绿）。

**Organization**: 按 spec.md 四个 user story 分组；SessionManager 端口扩展与异常映射为全部 story 的地基，集中在 Foundational。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 不同文件、无未完成依赖，可并行
- **[Story]**: US1=会话管理 / US2=一次性调用 / US3=信息查询与状态 / US4=管理台与风格规范

---

## Phase 1: Setup (Shared Infrastructure)

- [x] T001 在 `oryxos-boot/src/main/resources/application.yaml` 新增 `spring.mvc.async.request-timeout: 60000`（Boot 标准键，research R2；virtual threads/8080/springdoc 已就位零改动）

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: SessionManager 端口扩展与异常映射地基，阻塞全部 story

- [x] T002 [P] 新增 `SessionSummary` 与 `SessionPage` record 于 `oryxos-core/src/main/java/com/oryxos/core/session/`（字段按 data-model.md）
- [x] T003 [P] `oryxos-core/src/main/java/com/oryxos/core/session/Session.java` 加法：只读 `archived()` 标记与填充方法（既有构造器与签名不动）
- [x] T004 `oryxos-core/src/main/java/com/oryxos/core/session/SessionManager.java` 加法：`boolean archive(String sessionId)` 与 `SessionPage listSessions(int page, int size)` 签名（既有三方法逐字不动，用户已批准）
- [x] T005 [P] `oryxos-storage/src/main/java/com/oryxos/storage/session/SessionEntity.java` 加法：`markArchived(String archivedAt)` 变更方法
- [x] T006 测试先行：扩展既有 SessionManager/JPA 层测试（oryxos-storage 下既有 SessionManager 相关测试类），覆盖归档置位/幂等/未知 id 返回 false、分页倒序与 size 收敛、toRuntime 填充 archived——此时应红
- [x] T007 实现 `JpaSessionManager` 的 archive/listSessions 与 toRuntime 填充 archived（`oryxos-storage/.../JpaSessionManager.java`），使 T006 转绿
- [x] T008 测试先行：扩展 `oryxos-web/src/test/java/com/oryxos/web/api/GlobalExceptionHandlerTest.java`——新增 `AsyncRequestTimeoutException→504 AGENT_TIMEOUT`、`ProviderNotFoundException→503 PROVIDER_UNAVAILABLE`、`IllegalArgumentException→400 INVALID_REQUEST` 三映射断言（沿用既有"直接 new handler 调方法"单测风格）——此时应红
- [x] T009 在 `oryxos-web/src/main/java/com/oryxos/web/api/GlobalExceptionHandler.java` 新增上述三个 @ExceptionHandler 方法（既有映射逐字不动），使 T008 转绿
- [x] T010 H3 核实：翻本地 `spring-ai-openai-1.1.8` jar 确认 provider 调用失败的真实异常类型（预期 RestClientException 族）；核实成立则在 GlobalExceptionHandler 增补该类型→503 映射并补 T008 同风格断言；核实不到则不增补、在验收报告登记人工项（research R5）

**Checkpoint**: 端口扩展与异常地基就绪，story 可并行开工

---

## Phase 3: User Story 1 - 业务系统连续对话（会话管理） (Priority: P1) 🎯 MVP

**Goal**: 会话创建/发消息/查历史/归档/列表五端点，Controller 薄壳共享 CLI 同一引擎入口

**Independent Test**: `mvn -pl oryxos-web -am test -Dtest=SessionApiControllerTest` 全绿

- [x] T011 [US1] 测试先行：`oryxos-web/src/test/java/com/oryxos/web/api/SessionApiControllerTest.java`（@WebMvcTest 切片，@MockitoBean AgentService/SessionManager/ProfileRegistry）落地课件 harness 全部守点：①超 32KB→400 ②Session 不存在→404 ③正常请求 `agentService.process` 恰被调一次 ④**关键回归**：mock 抛 `IllegalStateException("jdbc:sqlite:/data/oryxos.db connect failed")` → 500 + `errorCode=="INTERNAL_ERROR"` + message 为统一话术"服务器内部错误"（用户裁决值）+ 响应体不含 "jdbc:sqlite"（方法名英译如 `internalError_neverLeaksDetails`，课件原文进 @DisplayName「内部异常细节_绝不能出现在500响应里」）⑤已归档会话发消息→400"会话已归档" ⑥创建缺字段→400、非法页参→400——此时应红
- [x] T012 [US1] 实现 `oryxos-web/src/main/java/com/oryxos/web/api/SessionApiController.java` 五端点 + 就近 DTO record（CreateSessionRequest/SessionSummaryResponse/SessionPageResponse/MessageRequest/MessageResponse/SessionMessageView/SessionDetailResponse，字段按 contracts/api-v1.md）：创建先查 ProfileRegistry 再 getOrCreate("web", userId, profileName)；messages 校验 32KB、get 404、archived 400 后调 `agentService.process`（Callable 包装）；详情截断最近 100 条；列表/归档调新端口方法
- [x] T013 [US1] `mvn -pl oryxos-web -am test` 跑通并修红（含 spotless:apply 后再测）

**Checkpoint**: US1 独立可验

---

## Phase 4: User Story 2 - 业务系统一次性无状态调用 (Priority: P2)

**Goal**: POST /agents/{name}/invoke 一次性会话跑完即返回，生命周期建→用→归档

**Independent Test**: `mvn -pl oryxos-web -am test -Dtest=AgentApiControllerTest` 全绿

- [x] T014 [US2] 测试先行：`oryxos-web/src/test/java/com/oryxos/web/api/AgentApiControllerTest.java`（@WebMvcTest 切片）：①正常调用返回 reply 且每次以新三元组建会话、完成后 `archive` 被调一次 ②未知 Agent 名→404 ③content 超 32KB→400——此时应红
- [x] T015 [US2] 实现 `oryxos-web/src/main/java/com/oryxos/web/api/AgentApiController.java`：`getOrCreate("invoke", "invoke-"+UUID.randomUUID(), name)`（research R4，H4④ 拼接纪律不破）+ `agentService.process` + 完成后 `archive`；先查 ProfileRegistry 把未知 Agent 转 404；Callable 包装；就近 InvokeRequest DTO
- [x] T016 [US2] `mvn -pl oryxos-web -am test` 跑通并修红

**Checkpoint**: US1+US2 均独立可用

---

## Phase 5: User Story 3 - 信息查询与系统状态 (Priority: P2)

**Goal**: profiles/memory/tools/health/info 五个只读端点 + 真实上下文冒烟

**Independent Test**: 切片测试 + `mvn -pl oryxos-boot test -Dtest=WebSmokeIT` 全绿

- [x] T017 [US3] 测试先行：`oryxos-web/src/test/java/com/oryxos/web/api/QueryApiControllerTest.java`（@WebMvcTest 指定四个查询 Controller）：五个端点 200 + 成功信封 + 契约字段形状（profiles 含 name/provider/model；tools 含 name/description；memory 含 backend/content；health 为 {status:"ok"}；info 含 providers[{name,status}]）——此时应红
- [x] T018 [US3] 实现 `ProfileApiController`/`ToolApiController`/`MemoryApiController`/`SystemApiController` 于 `oryxos-web/src/main/java/com/oryxos/web/api/`（分别注入 ProfileRegistry/ToolRegistry/LongTermMemoryStore/ProviderProperties+globalProviderNames，全部只读，就近 DTO）
- [x] T019 [US3] `oryxos-boot/src/test/java/com/oryxos/boot/WebSmokeIT.java`：@SpringBootTest(classes=OryxOsApplication.class, MOCK)+@AutoConfigureMockMvc；静态初始化块在模块 basedir 建 `.oryxos/profiles/` 夹具（.gitignore 已覆盖）；断言 /health、/info、/profiles、/tools 真实链路 200 + 信封形状；**不打 integration 标签**（无外部依赖，每次 mvn test 都守装配，research R7）
- [x] T020 [US3] `mvn -pl oryxos-web -am test` 与 `mvn -pl oryxos-boot test -Dtest=WebSmokeIT` 跑通修红

**Checkpoint**: US1-US3 全部独立可用

---

## Phase 6: User Story 4 - 只读管理平台与可复用风格规范 (Priority: P3)

**Goal**: /admin 五页只读管理台（Vue 3 + Vite）+ SPA 回落 + 风格 skill 固化

**Independent Test**: `npm run build` 产物存在；SPA 回落单测绿；quickstart 人工五页验收

- [x] T021 [US4] 写 `.agents/skills/oryxos-admin-ui/SKILL.md`：官网设计 token（取自 `website/.vitepress/theme/custom.css`，值逐字抄不自创）+ 工程约定（base '/admin/'、outDir 落 static/admin、SPA 回落、只调 /api/v1、只读无写按钮）+ 布局/三态/响应式规范 + 验收清单；跑 `scripts/link-agents.ps1` 同步到 .claude/skills 等软链目录（research 风格 skill 落点）
- [x] T022 [US4] 按 T021 的 skill 生成前端工程 `oryxos-web/src/main/frontend/`（Vue 3 + Vite，五页：会话列表 GET /sessions、Profile GET /profiles、Tool GET /tools、长期记忆 GET /memory、运行状态 GET /info；出错显示错误信封 message；空/加载/错误三态占位；响应式窄屏收导航；logo 用 website/public/logo.svg 拷入 public/）
- [x] T023 [US4] `cd oryxos-web/src/main/frontend && npm ci && npm run build`，产物落 `oryxos-web/src/main/resources/static/admin/`，确认 .gitignore 不拦截（research R6），构建产物准备随提交入库
- [x] T024 [US4] 实现 `oryxos-web/src/main/java/com/oryxos/web/config/AdminSpaWebConfiguration.java`（/admin/** 资源托管 classpath:/static/admin/，未命中回落 index.html）+ `AdminSpaWebConfigurationTest`（MockMvc：/admin 与 /admin/sessions → 200 text/html；/api/v1/ 路径不受影响仍走 API）
- [x] T025 [US4] `mvn -pl oryxos-web -am test` 跑通修红

**Checkpoint**: 管理台可被 serve 托管，四 story 齐备

---

## Phase 7: Polish & Cross-Cutting Concerns

- [x] T026 `mvn clean verify` 全绿（Spotless/P3C/Checkstyle/SpotBugs/FindSecBugs/Dependency-Check 全门禁，不跳过任何插件）
- [x] T027 更新 `AGENTS.md` 项目现状（010 web-service-admin 条目：11 端点、管理台、风格 skill、端口加法扩展）
- [x] T028 汇总 quickstart.md 人工项清单（真模型链路、共享存储互查、503/504 故障注入、200 并发、五页肉眼验收、swagger-ui）作为验收报告素材

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (T001)**: 无依赖
- **Foundational (T002-T010)**: 依赖 T001 无硬依赖可同开；**阻塞全部 story**（端口方法与异常映射被 Controller 使用）
- **US1 (T011-T013) → US2 (T014-T016) → US3 (T017-T020) → US4 (T021-T025)**: 均依赖 Foundational；US2 复用 US1 的 Callable/校验模式但可独立交付；US4 依赖 US1/US3 端点契约稳定（GET /sessions 等），排最后
- **Polish (T026-T028)**: 依赖全部 story

### Parallel Opportunities

- T002/T003/T005 三文件互不依赖可并行；T008 与 T006 不同模块可并行
- T010 为只读核实，任何时候可做
- US2/US3 的测试任务（T014/T017）在 Foundational 完成后可并行起草

---

## Implementation Strategy

1. T001 → Foundational（端口扩展 + 异常地基，测试先行）→ 检查点
2. US1（harness 三守点 + 关键回归落地）→ 独立验证（MVP）
3. US2 → US3（WebSmokeIT 守住装配）→ US4（管理台）
4. Polish：全量 verify + 文档同步 + 人工项清单

## Notes

- 课件交付物比对：六个 Controller ✓（T012/T015/T018）、GlobalExceptionHandler 扩展复用信封 ✓（T008-T010）、springdoc 集成既有零改动 ✓、三个 harness 测试类 ✓（T011/T008/T019）、配置 ✓（T001 + 既有）、前端与构建串联 ✓（T022/T023，R6 手动方案）、SPA 回落 ✓（T024）、风格 skill ✓（T021）。
- 比对差异（多出的）：T002-T007 SessionManager 端口扩展（用户批准的 clarify Q1）、T010 异常载体核实（research R5）、T014/T017/T024 三个额外测试类（harness 自然延伸，测试类非对外概念）。
- 反作弊提醒：不得删断言/@Disabled/放宽阈值；测试红了修实现。

## 第 27 节人推串联（复用 010，不开新 feature）

用户已批准 `lesson27-preflight.md` 的 D27-01/02/03；旧 T001–T028 的勾选仅代表 010 原交付。

- [x] T029 读取课件/技术方案/前序交付物，确认 60 核心类及 29 测试类存在，记录三项适配决议并建立第 27 节分支。
- [x] T030 [US1] 先补 `MockChatModelTest`，实现显式 `mock` 模型及 ProviderConfiguration 接线；仅模型使用合成响应，保留真实工具与审计。
- [x] T031 [US1] `oryxos.root` 统一现有 core/memory/tool/CLI 和默认 SQLite 路径，保持默认 `.oryxos`；按 D27-03 修复显式 Bootstrap 缺失与读取失败，回归默认配置及旧构造入口。
- [x] T032 [US1] 新增 boot `MockProviderFlowTest`：手工组合真实服务，SQLite 审计 2+1、真实 Memory 写入、完整角色历史、CLI/查询端点同源；覆盖三类失败的审计。
- [x] T033 [US1] 新增 boot `MockAgentE2ETest`：随机真实 HTTP 端口、临时工作区和库、生产装配/Provider 注册、两轮一次 Tool/跨会话记忆/隔离/前端资源；默认 gate 执行。
- [x] T034 [US1] 新增 boot `HumanTriggerFlowIT`（integration 标签）：真实模型/天气及 CLI/REST 两入口对账、Memory/工具三根支柱与失败路径；缺真实条件则如实保留未通过。
- [x] T035 [US4] 按 admin-ui 技能补会话只读详情，沿用现有接口/信封/分页；前端构建、三态和真实 HTTP 回落验证。
- [x] T036 更新 README 与 config/application.yml.example 的无 key mock 流程，记录实际请求字段和动态 sessionId；同步当前事实源与 AGENTS 的相关落点。
- [x] T037 执行受影响测试和不跳插件 `mvn clean verify`，记录门禁原始日志，核对 27 节 harness/交付物/H4/前序回归；不自动 commit/push。
- [x] T038 完成本节实现一致性复审与验收报告，准确列出真实网络和人工项，未执行的验收不可用默认组绿灯替代。
- [x] T039 修复 T033 真实 serve 组合发现的调度器依赖缺失：在现有 CoreEngineConfiguration 显式提供受 Spring 生命周期管理的 ThreadPoolTaskScheduler，仅常驻模式、用户 Bean 优先；保留 AgentScheduler 公开签名与默认 chat 不注册，重跑真实启动及前序装配测试。
- [x] T040 修复一致性审查 I27-01：StatusCommand 使用 CliFiles.workspace()，由 InitCommandTest 验证默认 init 后 status 同根，保留未设系统属性的默认行为。（L27-002）
- [x] T041 修复全仓门禁两次复现的 Mem0 契约夹具跨用例请求污染：共用临时 CA、每例独立 HTTPS 端口，上一端口关闭后才切 Dispatcher；保留全部次数/操作 ID/超时断言及 Mem0 生产传输实现，增加端口隔离与 TLS 延续验证。（L27-007）

追加补救任务的执行位置：T039 必须在 T033 最终确认前完成，T040 必须在 T031/T037 最终确认前完成；编号保留发现顺序，不表示运行顺序。

## 开发启停脚本补充（2026-09-07 用户请求）

- [x] T042 新增 bin/start.sh、stop.sh 与共享进程状态 helper：同一 JAR 启停 server/manager，读取外部 YAML，安全导入 DeepSeek .env 变量，首次配置/工作区初始化、日志、PID 身份校验及重复启停。
- [x] T043 添加 DeepSeek 开发 YAML 样例和本机忽略配置，保留 27 节 mock 样例；同步 Git 忽略/LF 规则、README 与 AGENTS。
- [x] T044 运行 Bash 语法检查与隔离 Git Bash/真实 JAR 生命周期测试，覆盖空白路径、YAML/安全 .env、重复启动、占用端口、PID 身份拒绝、数据保留及失败清理，记录 dev-launcher-verification.md；不自动提交/推送。

### 用户确认调整为前后端开发模式

- [x] T045 start.sh 默认启动后端 + Vite 两个进程（8080/5173），代理跟随后端端口；Node 启动器写原生 PID、保留严格端口、去除前端 DeepSeek 环境变量，stop.sh 先校验两个身份再停止，兼容旧后端单进程记录。
- [x] T046 验证 Vue/CSS 实际 HMR、跨端口 API 代理、双进程启停、失败回滚/恢复、密钥不进前端、端口与 PID 冲突拒绝及生产 build 兼容，记录 dev-mode-verification.md。
- [x] T047 README 与 AGENTS 明确开发双进程和发布单 JAR 的区别、Node/依赖要求及启动参数；保留原单进程验收为历史。
