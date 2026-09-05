# 010 Web Service 与第一版管理平台 验收报告（第 26 节）

日期：2026-09-05 · 分支：`026-lesson26-web-service` · feature：`specs/010-web-service-admin/`

## 一、六项证据 DoD

### 1. `mvn clean verify` 全绿（含 P3C/SpotBugs/FindSecBugs/PMD/Dependency-Check）

- 结果：**BUILD SUCCESS（EXIT=0，第八轮，约 6m42s）**，parent + 9 模块全部 SUCCESS
- 模块测试数（全绿）：core 62 / storage 21 / provider 8 / memory 133 / tool 146 / web 35 / channel-cli 7 / cli 5 / boot 5（含 WebSmokeIT 5）
- 中途修复（实现错修实现，门禁不让步）：
  1. core/storage 多处新 Javadoc 首句缺 ASCII 句号（仓内 SummaryJavadoc 规则要求 `.`，中文 `。` 不算）；
  2. SessionManagerTest 变量声明距首用过远（重排断言）；
  3. web 9 处同类 Javadoc + 1 处摘要小写开头（`^[a-z]` 禁则）；
  4. web SpotBugs 21 处：六个 Controller 类级抑制 `SPRING_ENDPOINT`（核心阶段无认证是 §7.5 明确边界，justification 注明）；两个构造器抑制 `EI_EXPOSE_REP2`（Spring 单例注入）；四个 record 加紧凑构造器 `List.copyOf` 防御复制（消 EI_EXPOSE_REP/REP2）；
  5. `WebSmokeIT` 类名连续大写：沿用 `checkstyle-suppressions.xml` 里 ProviderSmokeIT 的既有豁免先例，按课件既定字面量保留类名并注明本类默认组执行；
  6. oryxos-tool 一轮 surefire forked VM 崩溃（Windows 环境抖动，单跑 146/146 绿后复跑即过，非代码问题）。

### 2. 课件 harness 映射表逐类对号

| 课件测试类 | 落点 | 用例数 | 守点核对 |
|---|---|---|---|
| `SessionApiControllerTest` | `oryxos-web/.../api/SessionApiControllerTest.java` | 9 | 超 32KB→400 ✓；Session 不存在→404 ✓；正常请求 `agentService.process` 恰被调一次 ✓；归档→400、创建缺字段→400、未知 Profile→404、非法页参→400、详情最近 100 条截断 ✓ |
| `GlobalExceptionHandlerTest` | `oryxos-web/.../api/GlobalExceptionHandlerTest.java`（扩展既有） | 8（4 既有 + 4 新增） | 每类异常映射约定状态码（400/404/405/415/503/504/500）✓；响应体统一 `ApiErrorResponse` 信封 ✓；500 不含内部异常 message ✓（既有 `hidesUnexpectedExceptionDetails` 保持绿） |
| `WebSmokeIT` | `oryxos-boot/.../WebSmokeIT.java` | 5 | `/health`、`/info`、`/profiles`、`/tools` 真实链路可达 ✓；另断言 `/v3/api-docs` 可达（analyze E1）与 `/admin` 托管（转发 + 回落）✓；无外部依赖，默认组每次 `mvn test` 都跑 |

课件"最值钱的一个"关键回归落地：`internalError_neverLeaksDetails`，`@DisplayName("内部异常细节_绝不能出现在500响应里")`——mock 抛 `IllegalStateException("jdbc:sqlite:/data/oryxos.db connect failed")`，断言 500 + `errorCode=="INTERNAL_ERROR"` + `message=="服务器内部错误"`（用户裁决：沿用工程地基既定话术，不改课件字面值）+ 响应体不含 `jdbc:sqlite`。断言逻辑与课件逐条等价。

### 3. "本节交付物"逐项存在性

- 代码：六个 Controller（Session/Agent/Profile/Memory/Tool/System）✓ ls 核对；`GlobalExceptionHandler` 扩展既有、复用信封 ✓；springdoc 集成既有零改动 ✓
- 测试：三个 harness 类 ✓（见上表）+ 补充 `AgentApiControllerTest`(3)、`QueryApiControllerTest`(6)、`AdminSpaWebConfigurationTest`(3)
- 配置：`spring.threads.virtual.enabled=true`、`server.port: 8080` 既有零改动；新增 `spring.mvc.async.request-timeout: 60000`（application.yaml:25）；32KB/100 条限制在 `SessionApiController` 常量
- 前端：`oryxos-web/src/main/frontend/`（Vue 3 + Vite + vue-router，五页只读）✓；`npm run build` 产物落 `oryxos-web/src/main/resources/static/admin/`（index.html/assets/logo.svg）✓；构建串联二选一→手动 npm build、产物随仓库提交（research R6，mvn package 不依赖 Node）；SPA 回落 `AdminSpaWebConfiguration` ✓
- 风格 skill：`.agents/skills/oryxos-admin-ui/SKILL.md`（token 抄自 `website/.vitepress/theme/custom.css`）✓；`.claude/skills/oryxos-admin-ui` 经既有软链机制可见 ✓

### 4. 前序节测试回归

- 全量 `mvn clean verify` 覆盖全部模块既有测试（core 62 / storage 16+新增 / provider 8 / memory 133 / tool 146 / web 35 / channel-cli 7 / cli 5 / boot 含 WebSmokeIT 5），前序 harness 零回归。
- 跨节契约：`SessionManager` 三既有方法签名逐字未动（纯加法）；`GlobalExceptionHandler` 既有映射与既有测试保持绿；`GlobalExceptionHandlerTest.hidesUnexpectedExceptionDetails`（工程地基测试）未动。

### 5. H4 六条全局不变量自查

1. 涉外 IO：本节 Web 层不新增涉外 IO；工具执行仍走 24 节 Sandbox 链路 ✓
2. LLM/工具审计：引擎路径未绕开，`llm_calls`/`tool_invocations` 照常（invoke 用真实落库的一次性会话，跑完归档）✓
3. 无明文 key：新增代码 grep 无明文凭证；新增配置键无敏感值 ✓
4. session_id 拼接只在 SessionManager：Controller 只传三元组分量（invoke 的 `invoke-`+UUID 是 user 分量，非拼接 id）✓
5. 无 Reactor/CompletableFuture/自建线程池：Callable 体内同步阻塞，executor 框架托管（grep 零命中）✓
6. 无 Spring AI 自动 tool 执行：未触碰 provider 禁用路径 ✓

### 6. 剩余人工项（harness 判不了，等用户人工过）

见 quickstart.md 完整命令。清单：

1. 11 端点 curl 真链路（含真模型发消息），审计表有账；
2. CLI 聊过的 Session 从 `GET /sessions/{id}` 能查到（两入口共享存储）；
3. 断掉 Provider 拿 503、构造超 60 秒调用拿 504（真实故障注入，切片模拟不了容器超时派发）；
4. 200 并发 invoke 压测，虚拟线程稳定；
5. 管理台五页肉眼验收：真实数据渲染、无写入口、三态占位、`/admin` 与子路由刷新不 404（MockMvc 不重派发 forward，根路径真实转发行为在此验收）；
6. `/swagger-ui` 文档齐全。

## 二、过程决策（均已留档）

- **500 话术**：课件 harness 字面"内部错误" vs 既有"服务器内部错误"（工程地基测试钉死）→ 用户裁决保留既有话术，其余断言逐字保真（spec Clarifications）。
- **第 11 端点**：课件管理台提示词引用 `GET /api/v1/sessions` 但它不在 §7.2 核心 10 端点内 → 用户批准加入（只读、分页）→ spec FR-003。
- **归档/列表能力**：`SessionManager` 端口从未有归档实现（`archived_at` 列从未被写过）→ 用户批准纯加法扩展（clarify Q1），web 不新增 →storage 依赖边。
- **归档语义**：已归档会话发消息 → 400"会话已归档"，历史仍可读（clarify Q2）。
- **课件坑四（OpenAiAutoConfiguration eager 装配）**：`mvn dependency:tree` 证实 classpath 无 `spring-ai-autoconfigure-model-openai` 与 dashscope 构件，坑被 16 节"只依赖库 jar"结构性消除，不加 no-op 排除；WebSmokeIT 无 key 起真实上下文即回归证据（research R1）。
- **503 载体**：H3 核实 spring-ai-openai 1.1.8 jar——`OpenAiApiClientErrorException`（API 错误响应，直接 extends RuntimeException）+ `RestClientException` 族（网络层）；两者映射 503，对外固定话术不透 provider 侧细节；`ProviderNotFoundException`→503 透出 provider 名（research R5/T010）。
- **课件示意与现状差异**：①课件称"既有 IllegalArgumentException→400 映射"——现状无，本节补齐；②课件示例 `IllegalStateException→503` 与其自身 harness 回归矛盾，以 harness 为准不做该映射；③课件五个示意异常类不建，复用 `OryxException`+`ErrorCode`（research R8）。
- **超时机制**：Callable + `spring.mvc.async.request-timeout: 60000` + `AsyncRequestTimeoutException→504`；`AsyncRequestTimeoutException`（spring-web 6.2.19）与配置键（Boot 3.5.16 metadata）均本地 jar 核实（research R2）。
- **WebSmokeIT 形态**：落 oryxos-boot（系统级 IT 的家）；@SpringBootTest 真实扫描；静态块建 `.oryxos` 夹具（gitignore 已覆盖）；不打 integration 标签——无外部依赖，每次 `mvn test` 守装配（research R7）。web 模块无启动类导致的切片装配问题，控制器测试用 standalone MockMvc + 真实 GlobalExceptionHandler 化解。
- **SPA 根路径**：`ResourceHttpRequestHandler` 对空 lookup path 短路 404（不进回落解析器），`/admin`、`/admin/` 由视图控制器 forward 到入口页；MockMvc 不重派发 forward，根路径断言 forwardedUrl，内容断言落在子路由路径上。

## 三、边界（明确不做，未抢跑）

认证/API Key/JWT、SSE、WebSocket、限流、RBAC、Profile 增删改、Memory 写入端点、Webhook 触发、Prometheus 业务化、`/api/v1/agents` 增删改（29/30 节留白）；CORS 全开放保持核心阶段口径。
