# Quickstart: Notify 主动通知出口

## 自动化验证

前置：JDK 21、Maven；不需要真实 webhook、API key 或外网。

```bash
mvn -pl oryxos-tool -am test -Dtest=WebhookNotifyAdapterTest -Dsurefire.failIfNoSpecifiedTests=false
```

预期：

- 本地假 webhook 收到 POST；
- body 包含 `content`；
- URL 来自 `NotifyTarget`；
- 缺 URL 在请求前失败；
- 4xx、5xx 与网络异常向上抛。

模块回归：

```bash
mvn -pl oryxos-tool -am test
```

快速静态门禁（完整 OWASP 门禁按项目统一流程执行）：

```bash
mvn -pl oryxos-tool -am verify -Ddependency-check.skip=true
```

PowerShell 下将 `-D...` 参数分别加引号：

```powershell
mvn -pl oryxos-tool -am test "-Dtest=WebhookNotifyAdapterTest" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn -pl oryxos-tool -am verify "-Ddependency-check.skip=true"
```

## 人工项

本节第一批 Adapter 完成后只做接口中立性评审：换成另一渠道实现时，
`NotifyChannelAdapter.send(NotifyTarget, String)` 不应变化。

真实 webhook 送达、Profile 默认目标、Sandbox 先于发送以及 Tool 审计，要等第 20/24 节完成
`NotifyTools` 接线后验证，本节不伪造半成品冒烟。
