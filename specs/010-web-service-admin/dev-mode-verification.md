# 前后端开发模式验收（2026-09-07）

用户明确要求将单 JAR 开发启动改为后端 + Vite dev server。本记录取代 `dev-launcher-verification.md` 中的当前启动方式，原报告保留为历史。

## 交付与行为

- `bash bin/start.sh [后端端口=8080] [前端端口=5173]`：启动 Spring Boot 与 Vite 两个进程，等待后端健康、Vite 和代理就绪后成功返回。
- `bash bin/stop.sh`：先校验所有存活进程的启动标记，再分别停止 Vite/后端；不按进程名或端口查杀，保留工作区和日志。
- `_manager.mjs` 直接使用本项目已安装的 Vite `createServer`，与 `npm run dev` 同为开发服务器；Node 写入自身原生 PID，避免 Git Bash PID 转换问题。
- `vite.config.js` 保持 `/admin/` base 和原发布 outDir，开发时限定本机、严格端口，`/api` 通过服务端 `ORYXOS_API_TARGET` 代理到本次后端端口，文件服务限制在前端工程目录。
- 启动前检查 Node/Vite 依赖和两个端口；重复 start 幂等；前端掉线时补启动前端，既有后端保留；前端启动失败只回收本次新建进程。
- 后端继续从 `config/application.yml` 读取 DeepSeek 配置并解析 `.env` 占位；脚本加载的 DeepSeek 变量不传入 Vite 进程。

## 验证结果

Git Bash + 真实 JAR + Vite 7.3.6 + Chrome，使用独立、有空格的项目路径和随机 loopback 端口。仅使用合成配置与会话，没有调用真实模型或修改用户工作区。

**18 项通过**，证据目录 `.verification/dm-LaXG1l/`（`result.json`、`checks.log`、`hmr.png` 与两进程日志）：

1. 前后端相同端口拒绝。
2. 前端依赖缺失时不启动后端。
3. 前端端口被占用时不启动后端，不自动换端口。
4. 前端配置故障时回收本次新建后端。
5. Java 与 Node 为两个不同且可验证的原生 PID。
6. `.env` 命令替换文本不执行，前端配置确认未继承 DeepSeek key。
7. 自定义后端端口的 API 代理正确，YAML 唯一 DeepSeek Provider 已注册。
8. 前端 HTML 包含 Vite dev client，不是 JAR 静态页面。
9. 经前端代理 POST 创建的会话可从后端直接 GET 查到。
10. 修改隔离工程的 Vue 模板，浏览器即时更新；window 哨兵与 Node PID 保持，未刷新/重启。
11. 修改隔离工程 CSS，computed style 即时更新且未刷新。
12. Vite 文件请求读取根 `.env` 返回 403，无文件内容泄漏。
13. 重复 start 保持两个 PID。
14. 任意一个进程标记被篡改，stop 不停止两个进程中的任何一个。
15. 前端恢复失败不停止原有后端。
16. 恢复前端后后端 PID 不变。
17. stop 关闭两个进程并清除 PID 记录。
18. SQLite/日志保留，重复 stop 成功。

另有 `bash -n`、`node --check`、生产 `npm run build` 与 `git diff --check` 通过。生产构建产物哈希文件名保持 `index-CLwFtByg.css` / `index-CE1CcS_U.js`，页面样式未改。后端 JAR SHA256 仍为 `808ec436a5cf75b5caba427e2df4b9bc49694e4ec00daee2fc026b40dc6cfd5e`；本轮未改 Java 代码，不重复跑全仓 Java 门禁。

测试结束后对应 Java、Node 进程均已退出。Linux 分支通过 Bash 语法检查，本轮未在原生 Linux 环境实跑生命周期。

## 使用说明

默认管理台入口是 `http://127.0.0.1:5173/admin/`，Vue/CSS 改动直接热更新。Java 仍须重新打 JAR 并重启；正式发布仍采用 `npm run build` 后单 JAR 托管静态管理台。日志分别由 `.run/dev-server/server.logpath`、`manager.logpath` 指向，配置和数据不提交 Git。

需要 JDK 21+、Node 20.19+/22.12+；前端首次在工程目录执行 `npm ci`。本机源码未提交或推送。
