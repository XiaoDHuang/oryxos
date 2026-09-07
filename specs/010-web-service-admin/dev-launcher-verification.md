# 开发启停脚本验收（2026-09-07）

> 历史记录：本报告对应最初的单 JAR 启停。用户随后明确要求前端 dev server，当前入口已调整为后端 + Vite 双进程，最新证据见 `dev-mode-verification.md`。

用户要求在 bin 下提供 start.sh/stop.sh，方便手工启动 Server 和 Manager，DeepSeek 从 YAML 读取。复用当前分支与已有 fat JAR，无 Java 业务代码、端点或依赖变更。

## 交付

- `bin/start.sh [port]`：仓库根定位、默认 8080、本机监听、外部 YAML、按需 init、后台启动、健康与管理台就绪检查、重复启动保护。
- `bin/stop.sh [--force]`：校验 JDK VM 中的本次启动标记后停止记录的 PID，不按 Java 名称或占用端口查杀；保留日志、配置和工作区。
- `bin/_server-common.sh`：共享状态目录、路径处理、JDK 查找、进程校验及安全 .env 导入。
- `config/application-dev.yml.example`：DeepSeek 开发样例；本机 `config/application.yml` 已生成并忽略，原有 mock 样例保留。
- Git 仅放行上述三份 bin 脚本，固定 LF；`.run/` 与本机 YAML 不提交。

DeepSeek 的 name/api-key/base-url 由 Spring 解析 YAML。脚本仅将 `.env` 中 DeepSeek 的两个变量补入尚未设置的进程环境，不 source/eval .env，不输出密钥；模型仍在 Profile YAML 中指定。

## 运行证据

Windows Git Bash + 已构建真实 JAR，使用有空格的隔离项目路径与随机 loopback 端口，测试未调用 LLM。独立 `.env` 使用合成凭证与命令替换文本，用于证明输入没有被执行。

- `bash -n bin/start.sh bin/stop.sh bin/_server-common.sh`：通过。
- 完整生命周期与负例 **15 项通过**，结果 `.verification/sh-sWCFJg/result.json`，过程 `checks.log`。
- 覆盖：非法端口、从其他工作目录启动、空白路径、外部 YAML 唯一 DeepSeek Provider 已注册、安全 .env、首次工作区创建、API 与 Manager 同时可达、重复启动同一 PID、不同端口拒绝、已有端口拒绝、篡改进程标记拒绝停止、正常停止保留数据、重复停止、过期 PID 清理、坏 YAML 与缺失自定义配置失败处理。
- .env 中的 `$(touch ...)` 未执行，两个哨兵文件均不存在；没有将它当成 shell 代码。
- Git Bash 的 MSYS PID 与原生 WINPID 不同，且 nohup→Java 的 exec 阶段 WINPID 会变化。首轮发现过记录过早的问题，已修复为最终 JVM 启动标记可读后才记录；首轮测试进程已按精确 PID/标记验证后清理。
- 最新测试服务已停止，没有保留后台 Java 进程；隔离测试数据与日志保留在 `.verification`。

本轮未改 Java 源码，不重复运行上一轮 441 项/OWASP 全仓门禁；所用 JAR SHA256 保持 `808ec436a5cf75b5caba427e2df4b9bc49694e4ec00daee2fc026b40dc6cfd5e`。Linux 分支进行了 Bash 语法检查，未在本机执行原生 Linux 生命周期测试。

## 使用边界

脚本用于本机调试，源码/前端变更后先重建 JAR。需要 Bash、curl、JDK 21+ 的 java/jcmd。Linux 默认 TERM 等待退出，显式 --force 才在超时后 KILL；Windows 对经过身份校验的进程树执行 taskkill /T /F，不宣称生产优雅停机。停止不会删除 SQLite、Memory 或日志。

未提交/推送，未启动用户现有工作区，也未覆盖其 Profile 或 .env。
