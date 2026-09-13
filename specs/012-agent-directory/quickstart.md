# Quickstart: 插件化 Agent 目录验证指南

## 自动判卷（harness，机器门禁）

```bash
# 只跑本节六个测试类（预期全绿，Tests run 数必须非零）
mvn -q -pl oryxos-core -am test \
  -Dtest='AgentLoaderTest,DeriveProfileTest,AgentScanRegisterTest,ProfileRegistryRuntimeTest,AgentSchedulerRegisterTest,ProgressiveDisclosureTest'

# 全量门禁（预期 BUILD SUCCESS；本节完成的唯一定义）
mvn clean verify
```

逐类守点对照（详见 contracts/runtime-registration-api.md 与各测试 @DisplayName）：

- `AgentLoaderTest`：frontmatter/正文正确拆分；认出 scripts/skills/REFERENCE；缺 name/provider 报错点名。
- `DeriveProfileTest`：各字段正确映射；`schedules` 原样进派生 Profile。
- `AgentScanRegisterTest`：N 个目录 → 注册表 N 个；带 schedules 的都进了调度器。
- `ProfileRegistryRuntimeTest`：register 后立即可见；非法配置与启动路径同一异常同一消息。
- `AgentSchedulerRegisterTest`：registerProfile 后句柄表有句柄；cron/时区来自声明。
- `ProgressiveDisclosureTest`：正文进 system prompt；参考/子指令/脚本不预载。

## 手工路径（真模型，剩余人工项）

前置：`DEEPSEEK_API_KEY` 已配置；`OPS_WEBHOOK_URL` 指向可观测的 webhook 接收方。

```bash
# 1. 示例 Agent 落位（评审副本在 specs/012-agent-directory/samples/）
cp -r specs/012-agent-directory/samples/daily-reconcile .oryxos/agents/

# 2. 准备对账数据（脚本纯标准库，读两个 CSV 环境变量）
export RECON_ORDERS_CSV=/path/orders.csv
export RECON_SETTLE_CSV=/path/settle.csv

# 3. 常驻模式启动（scheduler 生效）
java -jar oryxos-boot/target/oryxos-boot-*.jar serve --port 8080
```

验证点：

1. **列表可见**：`GET /api/v1/profiles`（或 `oryxos profile list`）出现 `daily-reconcile`，全程零 Java 改动。
2. **定时来自 Agent**：到 `reconcile-morning` 触发点（或把 cron 改成近未来）自动执行；webhook 收到「✅ 对账通过」或分级报告。
3. **审计有账**：SQLite `llm_calls`/`tool_invocations` 有本轮记录；`scheduled_tasks`/`task_executions` 有对应行与终态。
4. **正文即时生效**：改 `.oryxos/agents/daily-reconcile/AGENT.md` 正文（不重启），下一次触发即用新说明。
5. **资源按需**：有差异时模型经 `read_file` 读 `skills/report-format.md`（tool_invocations 可见），脚本只有 JSON 产出进上下文。
