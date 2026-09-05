# Phase 0 Research: 010 Web Service 与第一版管理平台

全部"待澄清"已在 H0 依赖核对与本地依赖核实中解决，无遗留 NEEDS CLARIFICATION。

## R1: 课件坑四（Spring AI eager 装配）——结构上已不存在，不做排除

- **Decision**: `application.yaml` 不新增 `spring.autoconfigure.exclude`。
- **证据**: `mvn dependency:tree -pl oryxos-boot -am` 显示 classpath 只有 `spring-ai-openai:1.1.8`（库 jar）+ `spring-ai-model/commons/retry/template-st`，**没有** `spring-ai-autoconfigure-model-openai`、`spring-ai-starter-model-openai` 或任何 dashscope 构件。`OpenAiAutoConfiguration`/`DashScopeAutoConfiguration` 根本不在 classpath，eager 索要 `spring.ai.openai.api-key` 的坑在 16 节已被"只依赖库 jar"（oryxos-provider/pom.xml 注释明确此意）结构性消除。
- **Alternatives considered**: 照课件示例加两行 exclude —— 否决：目标类不在 classpath 时 exclusion 是静默 no-op（Spring Boot `checkExcludedClasses` 只校验 classpath 上存在的类），加了是配置噪音；FR-011 由 WebSmokeIT 在无任何 `spring.ai.openai.*` 配置下起真实上下文来证明。
- **风险登记**: 若未来有人把 `spring-ai-starter-model-openai` 引进依赖，坑四会复活——届时再补排除，并在该 feature 的 plan 记录。

## R2: Agent 调用 60 秒超时机制——Callable + MVC async timeout

- **Decision**: 两个调用引擎的端点（POST /sessions/{id}/messages、POST /agents/{name}/invoke）返回 `Callable<ApiResponse<...>>`；`application.yaml` 新增 `spring.mvc.async.request-timeout: 60000`（Spring Boot 标准键，非 OryxOS 自定义键）；`GlobalExceptionHandler` 新增 `@ExceptionHandler(AsyncRequestTimeoutException.class)` → 504 `AGENT_TIMEOUT`。
- **证据**: `org.springframework.web.context.request.async.AsyncRequestTimeoutException` 在本地 spring-web 6.2.19 jar 核实存在；`spring.mvc.async.request-timeout` 在 spring-boot-autoconfigure 3.5.16 的 `spring-configuration-metadata.json` 核实存在；`spring.threads.virtual.enabled: true` 已在 application.yaml（Boot 3.5 下 MVC async 走 virtual-thread executor）。Callable 体内仍是同步阻塞调用，不引入 Reactor/CompletableFuture/自建线程池（宪法 VII）。
- **Alternatives considered**: 手动 `Future.get(60s)` —— 否决，引入自建并发编排且绕开容器超时语义；WebFlux/SSE —— 宪法 VII 明禁。
- **可测性**: 切片测试把 `spring.mvc.async.request-timeout` 压到毫秒级 + 慢 mock，断言 asyncTimeout → 504，不必真等 60 秒。

## R3: SessionManager 端口扩展形状（用户已批准纯加法扩展）

- **Decision**: core `SessionManager` 新增两个方法，不改既有三方法签名：
  - `boolean archive(String sessionId)` —— 存在则置 `status="archived"` + `archived_at` 并返回 true，不存在返回 false；重复归档幂等（已归档再调返回 true，不覆写 archived_at）。
  - `SessionPage listSessions(int page, int size)` —— 按 `last_active_at` 倒序分页；page<0 或 size<1 抛 `IllegalArgumentException`；size>100 收敛到 100。
  - 新增 core 值对象：`SessionSummary`（sessionId/profileName/channel/userId/status/createdAt/lastActiveAt/archivedAt）与 `SessionPage`（page/size/total/content）。
  - 运行时 `Session` 增加只读 `archived()` 标记（`JpaSessionManager.toRuntime` 从实体 status 填充），支撑"归档会话发消息 → 400"判定。
- **证据**: 全仓仅 `JpaSessionManager` 一个实现（grep `implements SessionManager` 单点），纯加法安全；sessions 表已有 `status`/`archived_at` 列，无表结构变更。
- **Alternatives considered**: web 直连 SessionRepository —— 用户已否决（不新增 web→storage 依赖边，状态变更不绕过端口）。

## R4: invoke 的一次性会话身份

- **Decision**: `POST /agents/{name}/invoke` 用 `sessionManager.getOrCreate("invoke", "invoke-" + UUID.randomUUID(), name)` 保证每次调用全新会话（H4④：id 拼接仍只发生在 SessionManager 内部），`agentService.process` 跑完后调 `archive(sessionId)` 收尾——一次性会话生命周期完整（建→用→归档），会话列表页不被 invoke 的"活跃"残留污染。
- **Alternatives considered**: 复用固定三元组 —— 否决，幂等会让多次 invoke 共享历史、上下文互相污染；不落库 —— 否决，审计（llm_calls/tool_invocations 以 session_id 关联）需要真实会话行。

## R5: 503 的真实链路载体

- **Decision**: `GlobalExceptionHandler` 新增映射：`ProviderNotFoundException`（provider 模块既有 public 类型，Profile 引用了未注册 provider 时抛出）→ 503 `PROVIDER_UNAVAILABLE`；LLM 调用失败的传输层异常 → 503，其确切类型在实现期核实（预期 `org.springframework.web.client.RestClientException` 族——Spring AI 1.1.8 OpenAiApi 基于 RestClient；**写该映射前先在本地 jar/源码核实真实抛出类型，核实不到就只映射 ProviderNotFoundException 并在 acceptance 记录人工项**）。其余 RuntimeException 仍落兜底 500。
- **Alternatives considered**: 映射 `IllegalStateException`→503（课件示意代码）—— 否决，与课件 harness 回归直接矛盾（harness 用 IllegalStateException 期待 500），且会把无关内部错误误报成 Provider 故障。
- **注意**: `AgentService.process` 对未注册 Profile 抛 IllegalStateException——invoke/messages 两个 Controller 在调引擎前先查 `ProfileRegistry.find`/校验会话存在，把"Agent 不存在"显式转成 404，引擎的 IllegalStateException 保持 500 语义。

## R6: 前端构建串联——手动 npm build，产物提交进仓库

- **Decision**: 课件二选一选"前端单独 `npm ci && npm run build` 后再 `mvn package`"。前端工程在 `oryxos-web/src/main/frontend/`（Vue 3 + Vite，`base: '/admin/'`，outDir 指到 `../resources/static/admin/`）；**构建产物 `oryxos-web/src/main/resources/static/admin/` 提交进 git**，使 `mvn clean package` 单命令出含管理台的 fat JAR，不依赖 Node。
- **Alternatives considered**: frontend-maven-plugin 绑进 Maven —— 否决：构建期下载 Node/npm 发行版，离线/内网 CI 不友好，且给 `mvn clean verify` 增加网络抖动面；它是 plan 未列明的新插件，引入需单独批准。
- **证据**: `.gitignore` 已忽略 `node_modules/` 与 `dist/`（按目录名匹配），`static/admin/` 不被忽略，可正常提交。

## R7: WebSmokeIT 落位、形态与夹具

- **Decision**: 放 `oryxos-boot`（既有系统级 IT 的家，唯一能同时看到全部模块的叶子模块）；`@SpringBootTest(classes = OryxOsApplication.class, webEnvironment = MOCK)` + `@AutoConfigureMockMvc`，走完整 MVC 栈（含 GlobalExceptionHandler、TraceIdFilter）不起端口；测试类静态初始化块在模块 basedir 下建 `.oryxos/profiles/` 夹具（`.gitignore` 已忽略 `.oryxos/`，静态块在 JUnit 加载测试类时执行，先于 Spring 上下文创建）；**不打 `integration` 标签**——它不碰网络/模型/外部进程，目的就是每次 `mvn test` 都守 Bean 装配与 JPA 扫描（surefire 只按 `excludedGroups=integration` 排除，无标签即默认跑）。
- **Alternatives considered**: ApplicationContextRunner 拼装（既有 boot IT 模式）—— 否决：课件明确要 @SpringBootTest 真实扫描来抓 "Found 0 repositories" 类装配回归；打 integration 标签 —— 否决：无外部依赖的冒烟被默认排除会失去门禁意义。
- **无 key 行为**: 无 DEEPSEEK_API_KEY 时 ProviderConfiguration 跳过空凭据 provider（记错误日志不崩），ProfileLoader 跳过引用未知 provider 的 profile（记错误日志不崩），上下文照常起来，GET /profiles 返回空列表——冒烟断言只验可达性与信封形状，不验业务内容。

## R8: 错误处理扩展点——复用既有信封，课件示意的五个新异常类不建

- **Decision**: Controller 校验失败抛既有 `OryxException(ErrorCode.X, 消息)`（INVALID_REQUEST→400、RESOURCE_NOT_FOUND→404），既有 `handleOryxException` 已映射；`GlobalExceptionHandler` 只新增两个映射方法：`AsyncRequestTimeoutException`→504、`ProviderNotFoundException`→503。课件的 `SessionNotFoundException`/`InvalidRequestException`/`ProviderUnavailableException`/`AgentTimeoutException`/`ResourceNotFoundException` 五个示意类不建——`OryxException`+`ErrorCode` 已完整覆盖语义，双轨并存是文档外抽象。
- **500 话术**: 沿用既有 `ErrorCode.INTERNAL_ERROR` 的"服务器内部错误"（用户裁决）；课件 harness 回归测试的断言按此话术落地，`errorCode=="INTERNAL_ERROR"` 与"不含内部异常 message"逐字保真，课件原文进 @DisplayName。
- **信封分工**: 成功 `ApiResponse`（code/message/data/timestamp），错误 `ApiErrorResponse`（errorCode/message/timestamp）——既有地基即如此，harness 的 `$.errorCode` 断言天然满足；不新建 ErrorBody。

## R9: 配置增量

- **Decision**: `application.yaml` 仅新增一行 `spring.mvc.async.request-timeout: 60000`（Boot 标准键）。`spring.threads.virtual.enabled: true`、`server.port: 8080`、springdoc 路径、CORS 开放均已就位，零改动。
- **课件示例中的两行 `spring.autoconfigure.exclude`**：不落地（见 R1）。

## 风格 skill 落点

- **Decision**: `.agents/skills/oryxos-admin-ui/SKILL.md`（单一事实来源），课件的 `.claude/skills/oryxos-admin-ui/SKILL.md` 路径经既有软链机制达成（`.claude/skills → .agents/skills`，见 .agents/README.md 与 scripts/link-agents.ps1）；实现期跑一次 link 脚本同步。
