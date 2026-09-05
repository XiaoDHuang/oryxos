# Quickstart: 定时任务（第三种触发源）验证指南

## 自动化验证（harness 判卷）

```bash
# 本模块测试（含 AgentSchedulerTest 全部回归点与装配回归）
mvn -pl oryxos-core -am test

# 全量门禁（Spotless/P3C/Checkstyle/SpotBugs/FindSecBugs/PMD/Dependency-Check + 全部测试）
mvn clean verify
```

预期：全绿。`AgentSchedulerTest` 覆盖课件四回归点——CronTrigger 携带 cron+时区、锁占跳过、异常不外抛且 finally 放锁（二进宫）、三元组固定且两次触发同一 Session——外加 FR-008 非法规则跳过；`CoreEngineConfigurationTest` 补启用信号开/关两条装配回归。

## 人工验证（harness 判不了的剩余项）

前置：`.oryxos/` 已 init 且 Profile 配好可用 Provider（环境变量注入 API key）。

1. **真实到点触发**：给 `default` Profile 加：
   ```yaml
   schedules:
     - id: demo-minute
       cron: "0 * * * * *"
       zone: "Asia/Shanghai"
       message: "现在几点了？简单问候一句"
   ```
   `java -jar oryxos-boot/target/oryxos-boot-*.jar serve`，等到整分钟：日志出现定时触发记录；查 SQLite `llm_calls`（及如有工具调用的 `tool_invocations`）有这次自动执行的账。
2. **chat 不注册**：`chat --profile default` 交互期间跨过整分钟，无自动发起，`llm_calls` 无 scheduler 三元组的记录。
3. **改配置生效**：把 cron 改成 `"30 * * * * *"`（每分 30 秒），重启 serve，触发点按新时间走——零代码改动、零重编译。
4. **非法规则**：把 zone 改成 `Mars/Olympus`，重启 serve：启动不崩，日志有该条规则被跳过的错误记录，其余规则照常。

## 参考

- 配置块与启用信号契约：`contracts/schedule-config-yaml.md`
- 字段与校验明细：`data-model.md`
