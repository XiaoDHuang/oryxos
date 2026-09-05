# REST API 契约：/api/v1（010）

统一约定：

- 前缀 `/api/v1`；成功信封 `ApiResponse`：`{code:"SUCCESS", message, data, timestamp}`；错误信封 `ApiErrorResponse`：`{errorCode, message, timestamp}`。
- 状态语义：400 参数错误（INVALID_REQUEST）、404 资源不存在（RESOURCE_NOT_FOUND）、500 内部错误（INTERNAL_ERROR，话术固定"服务器内部错误"，不含内部细节）、503 Provider 故障（PROVIDER_UNAVAILABLE）、504 Agent 调用超时（AGENT_TIMEOUT）。
- CORS 核心阶段开放所有源（既有 WebMvcConfiguration，不动）。
- 认证/限流/SSE/WebSocket 不做（边界）。

## 会话管理（SessionApiController，/api/v1/sessions）

### POST / —— 创建会话
- 请求：`{"profileName": "default", "userId": "u-1"}`（均必填非空白）
- 200：`data = {sessionId, profileName, channel:"web", userId, status:"active"}`；同三元组幂等返回同一会话
- 400：字段缺失/空白；三元组分量非法（端口校验抛 IllegalArgumentException → 既有 400 映射……**注**：IllegalArgumentException 现状无显式映射，走兜底 500——实现期须在 GlobalExceptionHandler 补 `IllegalArgumentException→400 INVALID_REQUEST` 映射，课件所述"既有 IllegalArgumentException→400 映射保留不动"与现状不符，以现状+补齐为准，已在 tasks 立项）
- 404：profileName 未注册（Controller 先查 ProfileRegistry 再建会话，薄校验）

### GET /?page=0&size=20 —— 会话列表（第 11 端点，用户批准）
- 200：`data = {page, size, total, content:[SessionSummary...]}`，last_active_at 倒序
- 400：page<0 或 size<1；size>100 收敛为 100

### POST /{id}/messages —— 发消息（Callable，60s 上限）
- 请求：`{"content": "..."}`（必填，≤32KB）
- 200：`data = {reply}`
- 400：content 空/超长；会话已归档（"会话已归档"）
- 404：会话不存在
- 500/503/504：按统一语义；500 不泄漏内部细节（关键回归）

### GET /{id} —— 查历史
- 200：`data = {sessionId, profileName, status, totalMessages, messages:[{role, content, toolCalls?}]}`，messages 最多最近 100 条
- 404：会话不存在

### DELETE /{id} —— 归档
- 200：`data = null`（成功信封）
- 404：会话不存在

## Agent 调用（AgentApiController，/api/v1/agents）

### POST /{name}/invoke —— 无状态一次性调用（Callable，60s 上限）
- 请求：`{"content": "..."}`（同 MessageRequest 校验）
- 200：`data = {reply}`
- 404：Agent 名未注册
- 一次性会话：`getOrCreate("invoke", "invoke-"+UUID, name)`，完成后归档（research R4）
- 本节不做 /agents 的增删改（29/30 节留白）

## 信息查询

### GET /profiles（ProfileApiController）
- 200：`data = [{name, description, agentName, provider, model}]`

### GET /memory（MemoryApiController）
- 200：`data = {backend, content}`；content 为当前后端 `LongTermMemoryStore.load()` 全量视图（核心+归档窗口，语义同引擎注入）

### GET /tools（ToolApiController）
- 200：`data = [{name, description}]`，来自 `ToolRegistry.all()`

## 系统状态（SystemApiController）

### GET /health
- 200：`data = {status:"ok"}`

### GET /info
- 200：`data = {name, version, description, providers:[{name, status}]}`；status: registered（成功建模型）/unavailable（已声明但凭据缺失被跳过）；不主动探活（spec 假设）

## OpenAPI

- springdoc 自动生成，`/v3/api-docs` 与 `/swagger-ui.html`（既有 application.yaml 配置，对外入口/swagger-ui 由 springdoc 重定向页承载）。

## 管理台托管

- `/admin/**` 静态资源由 classpath:/static/admin/ 托管；未命中路径回落 `admin/index.html`（SPA 刷新不 404）；`/api/v1/**` 不受影响。
