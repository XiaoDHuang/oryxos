# Quickstart: Sandbox 白名单实现（第 24 节）验证指南

## 自动化验证（机器判卷）

```bash
# 本节全部测试（oryxos-tool 模块）
mvn -pl oryxos-tool -am test

# 关键回归单点
mvn -pl oryxos-tool test -Dtest=WhitelistSandboxTest
mvn -pl oryxos-tool test -Dtest=ToolConfigurationTest
mvn -pl oryxos-tool test -Dtest=FileToolsTest,ShellToolsTest,HttpToolsTest,NotifyToolsTest

# 全量门禁（含 P3C/SpotBugs/FindSecBugs/PMD/OWASP）
mvn clean verify
```

预期：全绿。`WhitelistSandboxTest` 覆盖三类"允许+拒绝"成对用例、`..` 穿越拦截、形似域名拒绝；四个 Tool 测试各含一条"白名单外输入被拦且底层 IO 未发生"。

## 人工验证项（harness 判不了的，留给人）

1. **真实链路集成验证**：`java -jar oryxos-boot/target/oryxos-boot-*.jar chat` 起真实会话，让 Agent 执行一条 `shell` 白名单外命令（如 `rm x`），确认：
   - 返回给模型的失败信息含"命令不在白名单内"字样；
   - SQLite `tool_invocations` 表出现 `success=false` 记录，`error_message` 人可读。
2. **接口中立性自查（思维练习）**：设想把 `WhitelistSandbox` 换成 `KataMicroVmSandbox`——`Sandbox.enforce(SandboxAction)` 签名不需要加任何方法即算通过。
3. **配置语义抽查**：把 `shell.allowed_commands` 置空重启，确认 `shell` 全拒绝而非不校验。

## 前置条件

- 无需 LLM 凭证（白名单校验不触网）；真实链路验证需配置任一 Provider key。
- 默认配置即可复现：`file.allowed_paths: [.oryxos]`、`shell.allowed_commands: [ls, cat, pwd, echo]`、`http.allowed_domains: [api.openweathermap.org]`。
