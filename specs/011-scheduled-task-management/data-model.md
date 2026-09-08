# Data Model: 011

本文件为设计，尚未修改 schema。两张表在既有 SQLite 内，实体归 storage/schedule，core 只有不可变视图与 Store 端口。时间使用 UTC epoch milliseconds（SQLite INTEGER），Java Entity 使用 Long，core/API 视图使用 Instant 并输出 ISO-8601；cron zone 保留 IANA 字符串。

## scheduled_tasks

| 列 | SQLite 类型/约束 | 含义 |
|---|---|---|
| task_id | TEXT PRIMARY KEY NOT NULL | Profile 配置中原 id，全局唯一，非空白 |
| profile_name | TEXT NOT NULL | 首次绑定后不可被相同 id 转移 |
| cron | TEXT NOT NULL | 合法规则快照 |
| zone | TEXT NOT NULL | 显式有效时区 |
| message | TEXT NOT NULL | 触发消息原文，非空白，沿用消息长度限制 |
| enabled | INTEGER NOT NULL CHECK IN (0,1) | 首次 true，此后由启停操作持久保存 |
| next_run_at | INTEGER NULL | 当前合法且启用规则的未来候选，其他为 NULL |
| last_run_at | INTEGER NULL | 最近实际开始时刻，未运行 NULL |
| last_status | TEXT NULL CHECK IN ('running','success','failed','timeout','unknown') | 从未开始 NULL |
| run_count | INTEGER NOT NULL DEFAULT 0 CHECK >=0 | 已成功登记开始的次数，运行中也计一次 |

不增加 Profile/租户身份新表；不直接复用 Session id 作为任务主键。task_id 与 profile_name 最大长度暂不另设文档外限制，入库/输出不允许作为未转义 SQL、HTML 或日志控制字符。

## task_executions

| 列 | SQLite 类型/约束 | 含义 |
|---|---|---|
| execution_id | INTEGER PRIMARY KEY AUTOINCREMENT | 每次执行的独立主键，也是相同开始时间下的排序键 |
| task_id | TEXT NOT NULL REFERENCES scheduled_tasks(task_id) | 任务关联，不级联删除 |
| session_id | TEXT NOT NULL | SessionManager 返回的 id，不在本表生成 |
| started_at | INTEGER NOT NULL | 实际准入时刻 |
| success | INTEGER NULL CHECK IN (0,1) | NULL=进行中、1=成功、0=失败/超时/未知 |
| error_message | TEXT NULL | 固定可公开的中文错误分类；成功 NULL |
| duration_ms | INTEGER NULL CHECK >=0 | 终结前 NULL；进程中断未知时也 NULL |

运行中限定 success/error_message/duration_ms 都 NULL；成功要求 duration_ms 非 NULL 且 error_message NULL；普通失败/超时要求 error_message 非 NULL、duration_ms 非 NULL；未知仅使用“进程中断，执行结果未知”并保持 duration_ms NULL。不要用虚假的零值占位未知耗时。

索引：`idx_task_executions_task_started` 覆盖 (task_id, started_at DESC, execution_id DESC)。外键不能只靠 SQLite 默认开关：Store 每次 begin 显式验证任务存在，测试核对无孤儿历史。没有删除接口。

## 视图

- ScheduledTaskView：taskId, profileName, cron, zone, message, enabled, nextRunAt, lastRunAt, lastStatus, runCount。Store 返回持久化值；API 额外 available 从当前 Scheduler catalog 派生，不落 DB。
- TaskExecutionView：executionId, taskId, sessionId, startedAt, success（Boolean）, errorMessage, durationMs（Long）。JSON 中 executionId 按十进制字符串输出，避免浏览器整数精度损失；Store/Java 内部为 long。
- 执行状态不新增数据库 status 列：running/unknown 用 nullable 组合判断；普通失败与超时分别使用受控错误分类。不要把 last_status 当作任意历史行的状态，因为它只指最近一次。

## 事务与竞争规则

1. **register**：新任务插入初始 enabled=true；同 id 同 Profile 只更新定义与 next_run；旧 enabled/counters/history 不变。同 id 换 Profile 拒绝，不能 UPDATE 所属。
2. **begin**：在短事务内插入 running 并更新 task.last_run_at/last_status=running/run_count+1，返回持久化 executionId。开始事务失败无引擎调用。Session 获取在此前；空 Session 可能因准入失败存在，但不伪造任务执行。
3. **finish**：以 executionId AND success IS NULL 为条件原子写终态，同时更新任务 last_status；不加次数。不把不同执行的结果覆盖最近状态。重复 finish 返回已有终态，不改变 success/duration/error。
4. **timeout**：watchdog 与 worker 竞争同一 finish；先发生的逻辑完成/截止由私有执行状态裁决，终态写入和等待信号需一致。终态写失败不报告成功，保留 running 供诊断/下次恢复；不得静默作为成功返回。
5. **recoverInterrupted**：仅本次进程启动、cron 安装前，对 success IS NULL 历史标记 false/固定 unknown 文本/duration=NULL，并将对应任务最近状态置 unknown；次数不变。禁止恢复当前仍活着的另一实例，部署假设一个工作区一个服务进程。
6. **enable**：只更新 enabled/next_run。与 begin 准入按同一任务的短互斥序列化，不等worker执行锁释放；已开始的执行不受停用影响。disabled 手动 begin 仍允许，automatic begin 则拒绝。
7. **definition disappearance**：不删除表行/历史，next_run_at 置 NULL；available=false。恢复同一 Profile 的规则仍保留 enabled 与历史。

数据库事务不跨 LLM/Tool/网络请求，不用 hibernate.ddl-auto=update，不修改旧四张表/Session JSON。首次升级、重复初始化、注入 finish 回滚、重复终态竞争、同 id 所属冲突都必须有真实 SQLite 验证。
