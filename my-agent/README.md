# my-agent 手工验证工作区

隔离环境验证 011 定时任务真模型链路（DeepSeek 真天气 + webhook 推送 + 启停/恢复）。不碰仓库根 `.oryxos/` 与 8080 开发服务。

## 准备

1. 一个**有效**的 `DEEPSEEK_API_KEY`（先验证：`curl -s -H "Authorization: Bearer <key>" https://api.deepseek.com/models`，返回 JSON 才算有效）。
2. 已构建的 fat JAR：`oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar`（没有就 `mvn clean package`）。
3. 打开**三个终端**：A=webhook 接收端、B=serve 实例、C=发命令。

## 启动

```bash
# 终端 A:回环 webhook,打印每个 POST 正文
python my-agent/webhook-receiver.py        # 监听 127.0.0.1:18099

# 终端 B:serve 实例(默认端口 18080,PORT=xxxx 可换)
# export DEEPSEEK_API_KEY=<有效key>
bash my-agent/start.sh
```

白名单已随启动注入 `api.open-meteo.com,localhost`（实例级环境变量，不改全局配置）。任务 `task-weather` 每 5 秒到点一次，也会出现在 `curl localhost:18080/api/v1/schedules` 里。

## 对账脚本（终端 C）

```bash
# 1) 手动执行一次:期望 success=true;终端 A 的 WEBHOOK_RECV 含实时气温数字
curl -X POST localhost:18080/api/v1/schedules/task-weather/run

# 2) 审计对账:该会话恰好 3 条 llm_calls + 2 条 tool_invocations(http_get/notify)
python -c "import sqlite3;c=sqlite3.connect('my-agent/oryxos.db');\
print(c.execute(\"select count(*) from llm_calls where session_id='scheduler:scheduler:weather'\").fetchall());\
print(c.execute(\"select tool_name,success from tool_invocations where session_id='scheduler:scheduler:weather'\").fetchall())"

# 3) 历史与次数
curl localhost:18080/api/v1/schedules/task-weather/executions

# 4) 停用:自动触发停止(终端 A 不再每 5 秒打印),但手动仍可执行
curl -X PUT localhost:18080/api/v1/schedules/task-weather -H 'Content-Type: application/json' -d '{"enabled":false}'

# 5) 域名拒绝:run 返回 success=false + 工具执行失败;审计出现 notify failed
curl -X DELETE 'localhost:18080/api/v1/sandbox/whitelist/entries?type=http&value=localhost'
curl -X POST localhost:18080/api/v1/schedules/task-weather/run

# 6) 恢复许可:启用后等 5 秒,真实 cron 自动成功一次(终端 A 再次打印)
curl -X POST localhost:18080/api/v1/sandbox/whitelist/entries -H 'Content-Type: application/json' -d '{"type":"http","value":"localhost"}'
curl -X PUT localhost:18080/api/v1/schedules/task-weather -H 'Content-Type: application/json' -d '{"enabled":true}'
sleep 6
curl localhost:18080/api/v1/schedules/task-weather/executions

# 7) 管理台
# 打开 http://localhost:18080/admin/schedules (定时任务页,唯一写操作页)
```

## 停止与清理

```bash
bash my-agent/stop.sh
# 想清档重验:停掉后删 my-agent/oryxos.db(任务/会话/审计全在里面)再 start
```

## 注意

- 真实模型有概率偏离严格 3+2 脚本（多调/少调工具）——看到计数偏差时先看该轮 `task_executions.errorMessage` 与 `llm_calls`，这正是验收要观察的信号。
- 验证完记得 `stop.sh` 并考虑停用/删除 `weather.yaml` 的每 5 秒规则，避免持续消耗真实模型额度。

## 012 插件化 Agent 验收（daily-reconcile 目录）

```bash
# 终端 A 不变(webhook 接收端 18099);终端 B 改用:
bash my-agent/start-reconcile.sh     # 白名单放行 python + 本工作区文件 + 回环 webhook

# 终端 C:
curl localhost:18080/api/v1/profiles | grep -o daily-reconcile        # 目录派生的 Agent 可见
curl localhost:18080/api/v1/schedules                                 # reconcile-morning 已注册
curl -X POST localhost:18080/api/v1/schedules/reconcile-morning/run   # 立即触发(同步等结果)
# 终端 A 应收到分级对账报告(数据造了 2 笔差异:A002 金额不符、A003 清算库缺失)
python -c "import sqlite3;c=sqlite3.connect('my-agent/oryxos.db');\
print(c.execute(\"select tool_name,success from tool_invocations order by started_at desc limit 5\").fetchall());\
print(c.execute(\"select status,error_message from task_executions order by started_at desc limit 1\").fetchall())"
# 期望:shell/notify(/read_file) 成功记录 + 最新 execution success=1
# 改正文即时生效:编辑 my-agent/agents/daily-reconcile/AGENT.md 正文(不重启),再 run 一次看 webhook 内容变化
```

### 实测记录（2026-09-13，execution 61→69→71→83）

- 最终成功：executionId=83 success=true 7.7s；链路 `python3 reconcile.py` → read_file report-format.md/REFERENCE.md → notify(P1 报告，分级正确：A003 missing_in_settle→P1、A002 0.01 尾差→P2) → save_memory。渐进式披露全程可见。
- 环境发现 1（平台坑，非 012 缺陷）：Windows+WSL 宿主机上，ShellTools 的 `ProcessBuilder("bash",...)` 被 CreateProcess 解析成 WSL 的 System32\bash.exe（System32 优先于 PATH）；WSL 里只有 python3 且默认不见 Windows 自定义环境变量。本工作区对策：白名单放 python3 + `WSLENV="RECON_ORDERS_CSV/p:RECON_SETTLE_CSV/p"` 翻译路径。建议后续在 20 节 ShellTools 评估解析 Git bash 绝对路径。
- 环境发现 2（存量边界 bug，非 012 引入）：失败/中断轮次会在 Session 末尾留下"前面没有 tool_calls 的孤儿 tool 消息"，下一次触发被 DeepSeek 400 拒绝（execution 69）。临时对策：删除该 session 行重跑；建议后续在 17 节 Session 收尾/截断处修复配对。
