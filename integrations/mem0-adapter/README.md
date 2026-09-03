# OryxOS Mem0 受控适配器（integrations/mem0-adapter）

随 Mem0 后端部署的受控 Python 组件：固定 SDK（`mem0ai 1.0.11+oryx.1`，仅回移官方 CVE-2026-7597 补丁）只做请求级暂存，真正的状态提交由本组件的 `oryx-memory-v1` 协议在 PostgreSQL/pgvector 中原子完成。**不使用原版 Mem0 REST server。**

默认关闭：只有 `memory.backend=mem0` 且配置齐全时 OryxOS 才接触它；本地 Markdown/SQLite 后端与它互不影响。

## 组成

- `src/oryx_mem0/`：协议五端点（capabilities / PUT·GET operation / POST snapshot / GET entries）、暂存引擎、PG 五表存储与逐调用审计。
- `migrations/001_initial.sql`：`oryx_memory` 五表（namespaces/operations/current/versions/call_audits），历史禁止 UPDATE/DELETE。
- `Dockerfile` / `postgres/Dockerfile` / `compose.yaml`：全部基础镜像按 digest 固定；适配器非 root 只读运行；PG 派生镜像移除 gosu、全程 postgres(999)、hold PG 包后应用发行版修复。
- `vendor/` + `patches/` + `scripts/sdk_build.py`：受控 SDK 的来源锁定与可重复构建证明。
- `build-manifest.json`：由 `scripts/build_manifest.py` 生成，绑定锁图、源码清单与真实构建 digest；`built_local` 只表示已按当前源码构建绑定，不是运行准入。

## 部署（compose profile `mem0`，默认不启动、不绑公网）

1. 准备 Secret 文件（全部经文件注入，不写进 YAML/命令行）：

   | Secret / 变量 | 含义 |
   |---|---|
   | `POSTGRES_DATABASE_FILE` / `POSTGRES_USER_FILE` / `POSTGRES_PASSWORD_FILE` / `POSTGRES_APP_PASSWORD_FILE` | PG 库名/引导账号/密码/应用角色密码 |
   | `ADAPTER_DATABASE_URL_FILE` | 单目标 postgresql URI（用受限角色 `oryx_mem0_app`；无默认值） |
   | `ADAPTER_CLIENT_BINDINGS_FILE` | JSON：工作区 UUID 与客户端 key 摘要绑定 |
   | `ADAPTER_CURSOR_SECRET_FILE` | 快照/游标签名密钥 |
   | `ADAPTER_LLM_API_KEY_FILE` / `ADAPTER_EMBEDDING_API_KEY_FILE` | 模型端点凭证 |
   | `ADAPTER_TLS_CERT_FILE` / `ADAPTER_TLS_KEY_FILE` | 适配器自身 HTTPS 证书（PEM，部署用正式 CA，不用测试证书） |
   | `ADAPTER_LLM_BASE_URL` / `ADAPTER_LLM_MODEL` / `ADAPTER_EMBEDDING_BASE_URL` / `ADAPTER_EMBEDDING_MODEL` / `ADAPTER_EMBEDDING_DIMENSIONS` / `ADAPTER_ALLOWED_ORIGINS` | 内网模型端点（HTTPS origin 精确白名单，逐请求校验） |

2. 首次启动会在空数据卷上创建受限应用角色（`scripts/postgres-init/00-create-app-role.sh`）；已初始化的卷请手工补齐同名角色。
3. 启动：`docker compose -f integrations/mem0-adapter/compose.yaml --profile mem0 up -d`。适配器仅绑定 `127.0.0.1:${ORYX_MEM0_PORT:-8443}`；模型出站走独立 `mem0-egress` 网络，出口策略由部署方施加。
4. OryxOS 侧：`memory.backend=mem0`，`memory.mem0.base-url/api-key/workspace-id` 与适配器绑定一致；缺 guard 或 capabilities 不兼容时启动失败。

## 运行语义

- **期限**：操作 30 秒（协议固定），客户端总确认 40 秒；超时/中断不代表失败，按 operation_id 查询终态。
- **NOOP**：输入已被处理且无需更新（合法空提炼/完全重复），原始输入与审计仍保留。
- **结果未知（OUTCOME_UNKNOWN / 503）**：提交后应答丢失等不确定场景。不重放 PUT；用 `GET /workspaces/{w}/operations/{id}` 查原 ID 的持久终态再判定。
- **历史只读核对**：`memory_versions` 承载每次 ADD/UPDATE/DELETE 的原始输入与前后值；常规召回只读 `memory_current` 当前有效条目。可直接用 SQL 只读核对，不提供历史查询 API、管理 UI、自动迁移或清理命令。
- **审计**：`memory_call_audits` 逐次记录 LLM/embedding 调用（usage、状态、操作关联）。这是适配器内部审计，不会出现在 OryxOS 的 `llm_calls` 表中。
- **安全停止**：`docker compose ... stop`（保留卷）；不要使用 `down -v`，除非明确放弃数据。

## 验证入口（测试用途）

- `tests/docker/harness.py setup --image oryxos/mem0-adapter:<t049-*> [--real-models]` 创建唯一命名的隔离部署（合成或本地真实模型），状态目录在 `.verification/` 下且含测试凭证，不得提交。
- 镜像/依赖门禁：`scripts/audit_dependencies.py`（来源绑定的依赖扫描）与 `tests/docker/report_image_audit.py`（逐项处置台账驱动的镜像门禁）。

## 版本

Python 3.12.14；FastAPI 0.141.1 / uvicorn 0.52.4 / psycopg 3.3.4；PostgreSQL 17.11 + pgvector 0.8.6；mem0ai 1.0.11+oryx.1。完整锁图见 `uv.lock` 与 `build-manifest.json`。
