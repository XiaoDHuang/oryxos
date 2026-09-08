# Contract: 定时状态、历史与管理

状态：设计稿，随 tasks 审阅。实现不得把本文件当作接口已发布。

## Core 端口与兼容

归 `com.oryxos.core.schedule`。Store 不引用 JPA、web DTO 或下游错误类型，视图字段见 data-model。

ScheduledTaskStore 计划方法：

```java
ScheduledTaskView register(String profileName, ScheduleConfig definition, Instant nextRunAt);
List<ScheduledTaskView> listTasks();
Optional<ScheduledTaskView> findTask(String taskId);
List<TaskExecutionView> listExecutions(String taskId, int page, int size);
long countExecutions(String taskId);
ScheduledTaskView setEnabled(String taskId, boolean enabled, Instant nextRunAt);
void updateNextRun(String taskId, Instant nextRunAt);
TaskExecutionView begin(String taskId, String sessionId, Instant startedAt);
TaskExecutionView finish(long executionId, boolean success, String errorMessage, Long durationMs);
void recoverInterrupted();
```

register 必须保留 enabled 并按实际保留值决定 nextRunAt；disabled 即 NULL，不能信任调用方基于默认 enabled=true 算出的候选。listTasks 按 taskId 升序，history 按 startedAt DESC/executionId DESC。页码从零，size 1–100；非法参数 IllegalArgumentException，缺任务 NoSuchElementException。count/分页读允许并发新插入造成总数快照差异，不能因此丢排序或重复终态。

AgentScheduler 保留 registerAll 与包私有 runOnce/lockFor 的测试入口；增加构造依赖 ScheduledTaskStore、受 Spring 管理的 SimpleAsyncTaskExecutor。生产装配必须提供 Store，不创建内存 Store 降级。旧四参构造使用点在同一变更更新，签名改变仅限课件指定的调度器改造；AgentService/SessionManager/MemoryService 不改。

新增管理方法：

```java
TaskExecutionView runNow(String taskId);
boolean isRegistered(String taskId);
ScheduledTaskView setEnabled(String taskId, boolean enabled);
```

runNow 在完成/截止后返回该次历史；找不到任务抛 NoSuchElementException；历史存在但当前规则不可运行抛 IllegalArgumentException；忙碌抛 JDK RejectedExecutionException，零新增历史。infra 错误不包装成普通业务失败。Store.find/list 查询历史，Scheduler.isRegistered 只用于派生 available/执行准入，不形成第二份状态。

ReActLoop 增内部 metadata 与中断检查，公共签名不变；所有新增公开概念均为课件点名类型/DTO和本节调度改造方法。课件外的通用结果模型、公共超时配置与错误码不新增。

## HTTP

统一前缀 `/api/v1/schedules`。成功复用 ApiResponse `{code,message,data,timestamp}`；协议失败复用 ApiErrorResponse `{errorCode,message,timestamp}`。ScheduleApiController 使用 ObjectProvider<AgentScheduler> 防止非调度模式导致整个 Web 应用装配失败。

| 方法/路径 | 请求 | 成功 data |
|---|---|---|
| GET 根路径 | 无 | 任务数组：ScheduledTaskView 全字段加 available:boolean |
| GET /{id}/executions | page=0,size=20（size≤100） | `{page,size,total,content:[执行视图]}` |
| POST /{id}/run | 无请求体 | 本次 TaskExecutionView |
| PUT /{id} | JSON `{"enabled":false}` 或 true | 更新后任务视图+available |

任务列表全量仅适用于核心十 Agent 规模；历史必须分页。task id 作为单段 URL，前端 encodeURIComponent；课件未规定新 id 格式，本节不擅自收窄既有规则字符集。含斜杠等服务器无法接受的路径 id 须在输入校验/测试中明确报错，不能操作错误任务。

PUT 用精确字段集合校验：null、缺字段、非 Boolean、额外 cron/message/任意字段均400；不能依赖全局 Jackson 忽略未知字段。禁止修改任务定义。GET 不改变执行状态；PUT 相同值幂等；POST 非幂等、不自动重试。

| 条件 | HTTP / errorCode | 历史变化 |
|---|---|---|
| 未知任务 | 404 / RESOURCE_NOT_FOUND | 无 |
| 非法参数、规则失效、已运行占用 | 400 / INVALID_REQUEST | 无 |
| 当前模式没有 Scheduler | GET 列表/历史可读（available=false）；POST/PUT 400 / INVALID_REQUEST | 无 |
| 已正常结束但模型/工具业务失败 | 200，data.success=false，固定 errorMessage | 一条失败记录 |
| 已正常成功结束 | 200，data.success=true | 一条成功记录 |
| 截止超时 | 504 / AGENT_TIMEOUT | 终态 timeout；若持久化故障另按500处理，已有 running 保留 |
| 未能开始/完成记账等基础设施失败 | 500 / INTERNAL_ERROR | 无 begin 或保留 running，不能伪报成功 |

POST 使用 Callable 复用现有 MVC 异步响应与 60 秒配置；Scheduler 自己的 watchdog 独立于请求取消。MVC 先返回504也不能导致执行线程无看护或重放；客户端通过任务历史复核。Controller 仅在本地捕获明确的三类 domain 异常后转既有 OryxException，其他异常交给全局处理；不修改所有端点的异常策略。

## 管理台

`/admin/schedules`：列表展示 id、Profile、cron+zone、下一时间、最近结果、次数、enabled、available；展开/选择查看分页历史，executionId 与 sessionId 等宽显示。新请求防旧响应覆盖，分页状态与空态明确。

- 仅 available=true 的任务可立即执行/启停；disabled 仍允许立即执行。
- 行级 submitting 禁止重复点击；成功后刷新任务及历史，不能只乐观改 UI。业务 failure 的200显示失败结果，不显示“执行成功”。
- 超时/网络断开显示“执行结果需通过历史确认”；不后台重发 POST。GET 错误重试允许，操作失败提示与列表读取失败分开。
- 页表与按钮键盘可达，375px 可用；日期显示用户可理解时间并保留 zone；沿用 website token、六页导航与 SPA 刷新。
- API 封装增加 POST/PUT，保持 apiGet 兼容；不引 UI 组件库、不添加其他管理写入口。
