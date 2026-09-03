# 007 Quickstart：实现后的验证指南

本文件是实施完成后的验证路径。各节命令均在本机真实执行过，证据日志在 `.verification/007-memory-backends/`（不入库）。已实现 ≠ 已封板：R5 全仓最终回归见第 7 节，未完成前 007 不归档。

## 1. 前置条件

- 006 基线、007 tasks 与实现已完成；本机 JDK 21/Maven、隔离的测试工作区。
- Java 依赖维持 plan 锁定版本；Python 3.12.14、受控适配源码、完整 uv.lock；外部镜像按 digest 固定。
- 独立测试 PostgreSQL 17.11 + pgvector 0.8.6；禁止连接已有业务库或使用生产记忆。真实自托管测试须使用获准的内网模型/embedding 和企业 Secret。
- PG测试只读取`ORYX_MEM0_TEST_DATABASE_URL`、`ORYX_MEM0_TEST_DATABASE_NAME`与`ORYX_MEM0_TEST_DATABASE_CONFIRM=DELETE:<database>`，不复用运行时DATABASE_URL。数据库名须匹配`oryx_mem0_*test`，数据库comment须由管理员预置为`ORYXOS_DISPOSABLE_TEST_DATABASE`；测试角色必须非superuser。远端测试库必须verify-full并给绝对CA路径，loopback可显式disable。缺任一条件时integration以错误退出，不skip或创建默认库。
- TLS 证书链受信任，Mem0 origin 显式加入 http.allowed_domains。测试可用专属 CA；禁止 trust-all 或默认绕过 guard。
- 外部绑定/允许目标严格采用适配协议§7.1的JSON数组；摘要不等于token，cursor密钥不与API token复用。示例仅示范格式，不能当作可用凭证。
- 按2026-08-31用户最新确认，代码、测试与所有回归均由主模型执行，不再调度Spark。

安全补丁增量按[SDK安全回移契约](contracts/sdk-security-backport.md)执行；仅有原始pip-audit命中数量不能判定受控回移构建是否通过，必须保留原始扫描并取得来源绑定的补丁回归/处置报告。

## 2. 本地后端

未配置 memory.backend：使用已有 Markdown 文件，检查旧内容、核心完整、归档 3999/4000/4001 字符边界与旧格式兼容。

配置 memory.backend=sqlite 并重启：仍使用工作区自己的 oryxos.db。验证 99/100/101 条归档、同毫秒 id 排序、窗口外关键词、大小写和 `%`/`_` 字面匹配；重启后已确认保存全部存在。回切 Markdown 不复制/删除任一后端数据。

```powershell
mvn -pl oryxos-memory,oryxos-storage,oryxos-tool,oryxos-core -am test
mvn -pl oryxos-boot -am test '-Dtest=MemorySystemIntegrationTest,MemoryBackend*IntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest.excludedGroups='
```

三后端（含 mem0 经真实 HTTPS 协议替身）覆盖：`MemoryBackendSystemIntegrationTest`（注入顺序/无空段/重启生效）、`MemoryBackendSwitchIntegrationTest`（6 向切换/回切/两工作区隔离/零网络与零行访问）、`MemoryBackendAuditIntegrationTest`（成功/拒绝/超时确认/未知/审计故障）。

预期：保留 006 断言；新增 Store 契约、SQLite Converter/Repository、配置选择、HTTP Sandbox及失败映射均通过。数据库探针证明未选 SQLite Memory 的行 SELECT/INSERT/UPDATE/DELETE 为零；共享 Session/审计连接与 schema 元数据初始化不算 Memory 行访问。

## 3. 外部组件的锁定与离线式单测

后续组件目录为 integrations/mem0-adapter。依赖由 pyproject.toml 声明，uv.lock 固定完整图；security 工具纳入受控 dev 组。首次解析锁文件、下载依赖和构建镜像属于实施阶段，不能在没有锁文件时使用 frozen 命令伪装锁定。

```powershell
uv sync --frozen --project integrations/mem0-adapter --python 3.12.14
uv run --frozen --directory integrations/mem0-adapter python scripts/sdk_build.py --check-installed
uv run --frozen --directory integrations/mem0-adapter pytest -m 'not integration'
uv run --frozen --directory integrations/mem0-adapter python scripts/audit_dependencies.py
```

预期：实际Mem0 1.0.11私有调用点加载；模型/embedding用测试替身，staging engine不能换成理想假Store。验证SDK摘要/结构和无默认真实存储、graph/reranker/遥测网络访问。坏JSON、第二阶段失败、越scope/绕过暂存写入及history失败均fatal，业务投影零变化；合法NONE的metadata-only暂存更新则为NOOP成功，保留原始输入和receipt，不强求SDK原本没有的history事件。

Python依赖及镜像扫描结果分别留证，不能用 Java OWASP 报告替代。旧版 SDK 的漏洞或兼容性失败必须解决并复核，不直接升级至 ADD-only 后称功能不变。

## 4. 真实事务与 HTTP 集成

先由部署人员按 [API 部署契约](contracts/mem0-adapter-api.md) 注入 Secret、内网地址、TLS 和 pgvector 维度；新组件只连接独立测试库。profile mem0 默认关闭，由验证人员明确启动。compose 文件须只绑定 loopback/获准内网，不能直接暴露外网。

```powershell
docker compose -f integrations/mem0-adapter/compose.yaml --profile mem0 config --quiet
docker compose -f integrations/mem0-adapter/compose.yaml --profile mem0 up -d
uv run --frozen --directory integrations/mem0-adapter pytest -m integration
mvn -pl oryxos-memory,oryxos-boot -am test '-Dtest=Mem0MemoryStoreContractTest,MemoryBackend*IntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest.excludedGroups='
```

隔离容器验收（真实镜像+HTTPS+PG；模型对端可合成或本地真实模型）：

```powershell
# 创建唯一命名隔离部署；输出为状态目录（含测试凭证，不入库）
uv run --frozen --directory integrations/mem0-adapter python tests/docker/harness.py setup --image oryxos/mem0-adapter:<t049-源码绑定tag>
# 真实本地模型（Ollama mistral-nemo:12b + bge-m3，GPU 回环，不出域）：
#   setup 时追加 --real-models；先 ollama pull 两个模型
$env:ORYX_MEM0_DOCKER_TEST_DIR = '<setup 输出的状态目录>'
$env:ORYX_DOCKER = 'D:/Docker/resources/bin/docker.exe'
uv run --frozen --directory integrations/mem0-adapter pytest tests/docker/test_deployed_api.py
uv run --frozen --directory integrations/mem0-adapter pytest tests/integration/test_runtime_smoke.py tests/integration/test_live_models.py
uv run --frozen --directory integrations/mem0-adapter python tests/docker/harness.py stop --directory $env:ORYX_MEM0_DOCKER_TEST_DIR
```

外部集成只对显式配置的测试环境运行；凭证/地址缺失应失败或标明未执行，不能以 skip 算验收通过。停止测试服务使用 compose stop；不要删除已有卷或运行 down -v。

每个PG用例前后fixture只删除已核验测试库中的固定`oryx_memory` schema；数据库名、确认串、PG 17.11、pgvector 0.8.6、非superuser及数据库comment任何一项不匹配时，不执行删除。测试库的创建、comment与权限配置由部署人员在测试外完成，fixture不获取管理员凭证或创建/删除数据库。

## 5. 可重放的验收场景

| 场景 | 操作与判据 |
|---|---|
| 核心完整 | 写超过100条核心；同一snapshot依次读取CORE/ARCHIVAL，两类各自计数一致。snapshot可复用，跨scope/snapshot的cursor必须拒绝，缺页整体失败 |
| 自动提炼与替换 | 先“项目Java17”，后“已升级Java21”，再重复新事实；当前归档只用有效结果，核心同名原文不被改写 |
| 历史持久 | 用只读测试数据库核对 operations 原文、versions 旧/新内容、current 引用及内部calls；重启后仍存在，普通召回不返回历史记录 |
| 历史写失败 | 在最后提交前注入版本写失败，current/namespace/COMMITTED receipt 均不变；原始输入与已产生调用审计仍可追溯 |
| 冲突 | 两个保存使用相同 baseline revision；至多一个按原基线提交，另一个明确冲突，不重推理 |
| 幂等 | 相同ID/相同正文只处理一次；相同ID/不同正文409；两次不同ID的相同正文允许合并但各保留原始输入 |
| 保存确认 | 提交后分别断链、返回畸形/超限200、错误hash/身份、缺receipt/history标记和5xx；均查原ID，只接受匹配持久终态。GET再拒绝/404/失效响应时仍UNKNOWN，不能改判未保存或重PUT |
| 崩溃恢复 | 分别在RECEIVED登记后抢占前、RUNNING阶段终止；登记时期限不变，过期后标ABORTED，不能永远202或自动推理；晚到owner不能提交 |
| 审计失败 | 内部调用开始审计失败则不发请求；结束审计失败禁止业务提交；UNKNOWN不得伪装COMPLETED |
| 安全负例 | 缺guard/key、跨workspace/跨scope、重定向、未许可目标、云回退、超大/缓慢响应全部明确失败 |
| 结果预算 | 验证64/65 facts、128/129动作、32KiB边界和内部1MiB边界；SAVE超预算在COMMITTED前失败，业务状态不变。20个合法条目经JSON转义超1MiB时RECALL返回完整排序前缀且标记true，不截正文、不伪装空结果 |
| 配置格式 | 绑定未知字段/重复摘要（含同工作区重复）拒绝，不同key同workspace允许；origin的CSV、路径、默认端口归一化后重复拒绝；Secret复用拒绝，错误不回显原值；Mem0正文/查询/生成文本含U+0000时拒绝且业务投影不变，hash分隔符仍用NUL |
| 工具链 | Mem0异常映射为success=false/retryable=false，超时/未知保留操作ID；SQLite审计failed+分类准确，不伪称status=timeout |
| 切换 | 三后端全部6个有向组合与回切；不自动迁移/双写/删除，未选后端零数据访问 |

## 6. 真实模型与全路径验收

在批准的合成数据集上用真实内网模型执行提炼、合并、替换、空事实和同义召回，逐项核对预先指定事实；不能因结果不符删除断言。检查服务、LLM、embedding、DB、日志出口与运行镜像，确认没有默认云流量、Secret或记忆日志外发。保存脱敏的部署配置摘要、网络拒绝证据和审计样本，不能只保存 capabilities 自报声明。

## 7. 封板命令与证据

```powershell
mvn clean verify
```

不跳过 OWASP、Spotless、P3C、Checkstyle、SpotBugs/Find Security Bugs 等插件。另保存显式 integration 结果、Python pytest/pip-audit、镜像漏洞与digest、6向切换和真实自托管验收证据，再执行实现一致性审查。

当前状态：R1（依赖/镜像安全）经逐项处置台账门禁通过（`t068-image-audit-gate-full.json`）；R2 事务机制、R3 API/出口、R4 真实本地模型均已通过；**R5 全仓封板回归（本节命令 + OWASP 不跳过 + 最终一致性审查）未执行，007 不归档**。
