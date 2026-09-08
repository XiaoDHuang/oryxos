# 第 28 节开工对账与范围建议

日期：2026-09-07。状态：**用户已确认 D28-01–04 推荐处理，进入 011 规格与设计，未进入实现**。

用户于本会话回复“同意”，批准上一轮列出的四项建议。工作分支 `028-lesson28-scheduler-management`，特性目录 `specs/011-scheduled-task-management`。后续设计中的常规边界选择会标为设计决策，不冒充用户逐项回答。

## 已核实基线

- 当前分支 `027-lesson27-human-trigger`，HEAD `c1e41a6`；开工检查工作区干净。27 节已提交并在上一轮推送，旧验收报告中的“尚未提交/推送”是历史快照。
- 009 定时任务原台账 15/15，010 当前台账 47/47。27 节报告记录默认测试 441/441、HumanTriggerFlowIT 2/2、WebSmokeIT 6/6、完整 verify 含 OWASP 通过；本次只核对既有证据，没有重新运行测试。
- 已完整读取第 28 节课件、第 16/17/18/19/20/22/24/25/26/27 节交付物小节，核对技术方案 §7、§8.2–8.7、§9–12 与宪法 v3.0.0。
- 核心前序类型存在性检查 54/54。当前 AgentScheduler 为 `oryxos-core/.../schedule/AgentScheduler.java`：Profile.schedules 启动注册、全局规则 id 去重、规则锁、scheduler 固定会话身份、异常捕获后继续。
- 课件第 20 节扩展工具尚未实现，已有用户批准的 DR-003 延期记录：edit_file/grep/glob/ask_user/web_search。继续保留债务，不把本节前置检查误报成这些扩展已完成。
- 当前没有 ScheduledTaskStore、ScheduledTask/TaskExecution 类型或本节三组测试。它们是第 28 节新增交付，不是前序缺失。

## 需要决议的差异

| 编号 | 课件与当前基线差异 | 推荐处理 |
|---|---|---|
| D28-01 | 课程 Skill 将 28 归为“不新建 feature”的串联课；最新课件已增加两表、持久化端口、四 API、写操作页面 | 将新版 28 按独立 feature `011-scheduled-task-management` 推进，009/010 保留验收历史；同步课程 Skill 的 28 路由，走完整 specify/clarify/plan/tasks/analyze 流程 |
| D28-02 | 技术方案 §7.3/8.5 将调度管理留在扩展阶段；课件将状态、历史、立即执行、启停纳入本节 | 批准这一有限核心范围扩张；同步四份事实源与 AGENTS，仍保持九模块；本节不包含创建/删除任务、编辑 cron/message 或动态创建 Agent |
| D28-03 | 课件 §2.1/2.2 写“skill 的 schedules”，技术方案与宪法当前入口为 Profile YAML；作者格式派生属于后续节 | 本节继续从 Profile.schedules 读取定义，Skill 提供任务正文；29 节作者格式若扩展也派生到同一 Profile。本节不新增 Skill 调度解析器或第二定义源 |
| D28-04 | 课件要求天气推送恰好两次 LLM；技术方案 §12.1 与当前 ReActLoop 是先取天气、再依天气决定 notify、最后收尾 | 严格顺序成功场景按三次 LLM、两次 Tool 对账，并验证 webhook 内容使用本次天气结果；真实模型出现额外调用须显式记录为不符合该脚本，不放宽为“至少” |

D28-04 的依据：同一轮模型生成 notify 参数时尚未看到同轮 http_get 的执行结果。要确保推送依赖实际天气，需要下一轮生成 notify；工具结果回填后还需无 Tool 的最终响应。因此不能为了凑两次 LLM，在查天气前就生成推送内容。

管理台视觉继续以 website 实际 token 为准；仅定时页增加经批准的操作，其他页面保留只读。实施时同步 admin-ui Skill 对该页的例外。

现有宪法已经允许状态外置、声明式 Profile 和九模块内端口加法；上述推荐范围预计不需要改变宪法原则。正式 plan 仍须逐条检查；如需修订宪法，另列精确条款并获得显式批准，不能把本次范围建议当作宪法修订授权。

## 批准后拟实施的交付拆分

1. **US1 任务登记与持久化**：core 的 ScheduledTaskStore、ScheduledTaskView、TaskExecutionView；storage 的 JPA 实体、仓储、实现及两张表手工迁移。验证旧库升级、重复启动、启停状态与历史保留。
2. **US2 执行与控制**：现有 AgentScheduler 接入登记、状态检查、成功失败记账、runNow；手动执行无视 enabled，但沿用防重叠约束；自动跳过不增加历史和 run_count。
3. **US3 API 与管理台**：ScheduleApiController 四接口（GET 列表、GET 历史、POST run、PUT enabled）、DTO、定时任务页面、README；保持既有信封和错误处理机制。
4. **US4 串联与恢复**：ScheduledTaskE2ETest 默认无 key；SchedulerFlowIT 真模型/天气与测试 webhook；RestartRecoveryIT 独立进程重启；多 Profile 工具、Session、调度隔离及稳定性检查。

两个新增表承接课件字段：

- scheduled_tasks：task_id / profile_name / cron / zone / message / enabled / next_run_at / last_run_at / last_status / run_count。
- task_executions：task_id / session_id / started_at / success / error_message / duration_ms。

task_executions 每条记录的主键、约束、索引与迁移版本在 plan 锁定；不能直接把 task_id 当作执行历史的唯一主键。

## clarify / plan 必须闭合的语义

- **任务身份与配置变更**：兼容当前全局唯一规则 id；重启登记不能把 enabled=false 或 run_count 清零。配置删除、同 id 换 Profile、cron 修改后的历史归属须明确。
- **执行成功定义**：AgentService.process 返回 String；Tool 失败会回填模型，并不必然抛到 Scheduler。不能只凭 process 正常返回就宣称推送成功。需锁定任务级成功与工具级失败的关系及可观测实现方式。
- **重启中断**：课件只列执行结果字段，未定义执行到一半被 kill 的状态。区分已完成记录恢复与进行中任务恢复；不虚报 exactly-once 或自动重放。
- **下一触发时间**：停用、立即执行、过期触发、进程停机及重启后 next_run_at 的含义须统一。
- **并发与截止时间**：同任务重叠、同 Profile 多任务共用 Session、多 Profile 间阻塞隔离、60 秒截止是否真正取消执行，必须用行为测试确认。
- **API 语义**：未知任务、忙碌任务、服务非调度模式、历史分页、PUT 只接受 enabled、POST 完成响应与异常映射须锁定。
- **运行环境**：测试用独立临时工作区、数据库、端口和 webhook 接收端。重启测试只停止自己的子进程；保留正在供用户调试的 8080/5173 服务。
- **Demo 外部条件**：精确域名白名单、通知目标、新闻源/MCP 按实际配置核对；本机测试 webhook 的通过不冒充企业 IM 人工验收。

## 当前停点

开工对账阶段只产出本文。用户确认后允许创建 011、同步事实源/AGENTS/相关 Skill 并推进规格与设计；业务代码、数据库及运行配置仍待 tasks 审阅后实施。

开工软门禁第 3 条已由本次用户确认解除。tasks 产出后仍按课程 Skill 的固定停点交付审阅；范围批准不代表实现、测试或验收通过。
