# Quickstart: 011 验证指南

当前为实现前指南，以下成功结果均待执行；不能从本文推断实现已存在。

## Demo 前置清单（部署侧）

- 天气源精确域名 `api.open-meteo.com` 与通知渠道域名必须显式进 `http.allowed_domains`（演示可用运行时白名单端点临时加，或改 application.yaml 重启）。
- 演示通知与真实企业通知分开：测试只用回环接收端（MockWebServer 或等价物），接收记录不冒充企业群验收。
- 新闻源/MCP 按实际提供方配置；`OpenAiAutoConfiguration` 排除在 26 节已结构性成立（classpath 无自动装配构件）。
- DR-003 扩展 Tool 债务保留（edit_file/grep/glob/ask_user/web_search 未实现，不冒充）。

## 前提

- JDK21、仓库锁定 Maven 依赖；Java/SQLite无需 Docker。使用主模型回归。
- 以新的临时工作区、SQLite 和回环端口运行测试；不要停止用户的 8080/5173 服务，不重启计算机。
- 真链路只需向测试进程注入 DEEPSEEK_API_KEY；完整安全门禁需要 NVD 凭证。根 .env 不是 Maven 自动加载源，沿用安全解析流程、不回显值。未提供真实条件时必须记录未执行。

## 验证命令

```powershell
# 默认本节无 key 验证（实现后应全部通过；各测试选择错误必须失败）
mvn -pl oryxos-boot -am test '-Dtest=ScheduledTaskE2ETest,MultiAgentIsolationTest,SchedulerStabilityTest' '-Dsurefire.failIfNoSpecifiedTests=false'
# Store、Scheduler 和跨节引擎回归
mvn -pl oryxos-storage -am test '-Dtest=JpaScheduledTaskStoreTest,AgentSchedulerTest,ReActLoopTest,CoreEngineConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false'
# 本节真实链路+独立进程重启（integration必须显式开启）
mvn -pl oryxos-boot -am test '-Dtest=SchedulerFlowIT,RestartRecoveryIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest.excludedGroups='
# 前序WebSmokeIT默认命名不会匹配，显式执行
mvn -pl oryxos-boot -am test '-Dtest=WebSmokeIT' '-Dsurefire.failIfNoSpecifiedTests=false'
# 完整门禁：所有插件包括 OWASP，不跳过
mvn clean verify
```

Windows 如复现已记录的跨盘 Surefire 或 Unix socket 长路径问题，使用 27 验证脚本记录过的进程级兼容参数与独立短目录；不要把它们写入生产配置。

前端在 `oryxos-web/src/main/frontend` 执行 `npm ci`、`npm run build`，产物进入 static/admin；当前 npm 不在 PATH 时按 27 证据使用已验证的本地 npm runtime。先前端 build、再最终 verify；不要仅重跑 Java 后声称 JAR 包含最新前端。

## 逐项观察

1. 临时 mock Profile 配远期 cron 和“记住：测试事实”，启动整机；GET schedules 有启用任务、runCount=0、未来 nextRunAt。
2. POST run：真实 ReAct/Memory，2次 mock LLM+1次save_memory；GET列表次数=1，历史一条 success=true，GET memory 可查原文。这个 mock 场景计数不是天气三轮脚本。
3. PUT enabled=false；真实 cron 或捕获的生产触发回调进入时零模型/历史增量；手动运行仍成功，停用状态保留。
4. 两次真实天气各3次LLM+2次Tool（http_get/notify），webhook收到包含本次天气的内容，Session相同；拒绝webhook后失败审计存在，恢复许可后下一次真实cron可运行。
5. 独立JVM完成后kill并重启同目录；旧会话/核心记忆/两类调用审计/任务及历史全保留，停用仍停用。另在running记录落库后kill，重启应unknown且不重放。
6. A仅文件、B仅HTTP，交替调用；A失败/受控阻塞时B在A截止前完成。任务和Profile锁占用均不创建多余历史。
7. 真实60秒截止至少一次：504、取消信号、后续Tool零启动、迟到结果不覆盖timeout；不响应中断的受控夹具需保持占用直到释放。MCP不存在仍可启动并查健康端点。
8. 实际新fat JAR起服务，验证四API、六页SPA刷新；375px/桌面、空/加载/错误/重试、写按钮防重入、200失败视图及超时历史复核。

## 证据与人工边界

日志/临时库/截图写忽略目录 `.verification/lesson28/`，验收报告提交到本 feature；列出确切测试数、失败原始记录、JAR摘要、实际端口关闭证明与密钥不回显扫描。

部署方仍需提供批准的企业通知域名/渠道、新闻源或新闻MCP，按精确域名白名单验证；不擅自填通配符或放开全部网络。测试webhook接收不冒充企业群验收。DR-003五个扩展Tool仍待后续补充；四小时性能观察和第29/30/31节产物不属于本节已完成内容。
