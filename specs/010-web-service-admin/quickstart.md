# Quickstart 验证指南：010 Web Service 与第一版管理平台

## 自动化验证（harness）

```bash
# 本节全部自动化门禁（含 WebSmokeIT 真实上下文冒烟，无需任何 API key）
mvn clean verify

# 只跑本节 harness 三类
mvn -pl oryxos-web -am test -Dtest=SessionApiControllerTest
mvn -pl oryxos-web -am test -Dtest=GlobalExceptionHandlerTest
mvn -pl oryxos-boot test -Dtest=WebSmokeIT
```

## 人工验证（真链路，需 DEEPSEEK_API_KEY）

前置：`export DEEPSEEK_API_KEY=...`（只需这一个 key——若启动索要 `spring.ai.openai.api-key`，说明 FR-011 回归，回去查依赖树），工作区已 `init`。

```bash
java -jar oryxos-boot/target/oryxos-boot-*.jar serve          # 默认 8080

# 1. 会话全链路
curl -X POST localhost:8080/api/v1/sessions -H 'Content-Type: application/json' \
     -d '{"profileName":"default","userId":"u-1"}'            # 拿 sessionId
curl -X POST localhost:8080/api/v1/sessions/{id}/messages -H 'Content-Type: application/json' \
     -d '{"content":"今天北京天气怎么样"}'                     # 真模型回复；查 llm_calls/tool_invocations 有账
curl localhost:8080/api/v1/sessions/{id}                       # 历史（≤100 条）
curl 'localhost:8080/api/v1/sessions?page=0&size=20'           # 列表含该会话
curl -X DELETE localhost:8080/api/v1/sessions/{id}             # 归档
curl -X POST localhost:8080/api/v1/sessions/{id}/messages -H 'Content-Type: application/json' \
     -d '{"content":"还在吗"}'                                 # 期望 400「会话已归档」

# 2. 一次性调用与信息查询
curl -X POST localhost:8080/api/v1/agents/default/invoke -H 'Content-Type: application/json' \
     -d '{"content":"用一句话介绍你自己"}'
curl localhost:8080/api/v1/profiles
curl localhost:8080/api/v1/tools
curl localhost:8080/api/v1/memory
curl localhost:8080/api/v1/health
curl localhost:8080/api/v1/info                                # 含 provider 连通状态

# 3. 错误语义抽查
curl -X POST localhost:8080/api/v1/sessions/nope/messages -H 'Content-Type: application/json' \
     -d '{"content":"hi"}'                                     # 404
# 构造 >32KB content                                                            # 400
curl -X POST localhost:8080/api/v1/agents/ghost/invoke -H 'Content-Type: application/json' \
     -d '{"content":"hi"}'                                     # 404

# 4. 管理台与文档
open http://localhost:8080/admin                               # 五页渲染真实数据、无写按钮
open http://localhost:8080/admin/sessions 直接刷新             # 不 404（SPA 回落）
open http://localhost:8080/swagger-ui.html                     # 端点文档齐全
```

其余人工项（课件「做完怎么验」）：

- CLI 聊过的会话从 GET /sessions/{id} 能查到（两入口共享存储）。
- 断掉 Provider 拿 503、构造超 60 秒调用拿 504（依赖真实故障注入）。
- 200 并发 invoke 压测，虚拟线程扛得住。
- 管理台五页肉眼验收：空/加载/错误三态占位、风格与官网首页一致。

## 前端构建（开发期）

```bash
cd oryxos-web/src/main/frontend
npm ci && npm run build      # 产物落 ../../resources/static/admin/（提交进 git）
```

产物已随仓库提交时，`mvn clean package` 单命令即出含管理台的 fat JAR，无需 Node。
