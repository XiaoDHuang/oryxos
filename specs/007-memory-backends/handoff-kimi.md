# 007 Memory 三后端：Kimi 接手说明

交接日期：2026-09-03。用户要求“交接一下任务吧，我让 Kimi 完成”。本文件是交接快照，不表示 007 完成，也不构成安全豁免或新的范围批准。本轮仅整理交接，不继续实现或启动测试。

## 1. 先看结论

- 仓库：`D:/project/AI-Coding/OryxOs`；分支：`codex/007-memory-backends`。
- 当前 HEAD：`5333e77`，`feat(memory): add durable SQLite backend and local switch coverage`。
- `tasks.md` 实际 **64/80 完成，16 项未完成**：T049、T061–T075。T080 是真实容器测试发现问题后追加并完成的补救任务。
- Markdown/SQLite 已有实现与本地验收；Java Mem0 Store、Python 受控适配器、真实 PG 与隔离 Docker 分层验证已推进，但 **R1/R3/R4/R5 未通过，Mem0 仍不可启用，不能归档 007 为完成**。R2 先前的事务机制门禁通过不等于完整运行验收。
- 用户更新 Windows 后 Docker 已恢复，不能继续沿用旧的“Docker 无法启动”结论。当前阻塞是 **镜像安全未通过**，以及 **缺少明确获准的真实内网 LLM/embedding 环境**。
- US3 大量实现尚未提交。保留整个当前工作区，不能 reset、clean、覆盖未跟踪文件或只取 HEAD 后重新实现。

## 2. 接手阅读顺序

下面未写盘符的路径均相对仓库根目录。

1. `AGENTS.md` 与 `.specify/memory/constitution.md`（v3.0.0）。
2. 本目录的 `spec.md`、`plan.md`、`tasks.md`、`acceptance.md`，尤其 acceptance 最后的“Docker恢复、T049真实构建与T080补救”。
3. `contracts/mem0-adapter-api.md`、`contracts/sdk-security-backport.md`、`contracts/memory-contract.md`，以及 `data-model.md`、`research.md`、`quickstart.md`。
4. [镜像安全复核](image-security-review.md)，再读完整原始扫描，不能只看汇总计数。
5. 发生范围/架构争议时回到 `docs/TechnicalSolution.md` 等四份事实源与 `docs/decisions/007-memory-backends-scope.md`。

旧的日期段落可能仍写“未实现”“Docker故障”“63/79”，是历史记录。以最新任务勾选、最新日期证据和实际代码交叉核对，不把旧记录全部改成成功，也不能遗漏后来发现的缺口。Specify CLI 锁定 0.14.2，不升级或引入 extension。

## 3. 已有变更与不可丢失的实现

### Git 与工作区

- `3d60ee0`：006 文件 Memory 归档；`2a63e58`：007 US1；`5333e77`：007 US2 SQLite。
- 后续尚未 commit/push；`git status --short` 中有大量修改和未跟踪文件，这些是累计的 feature 工作，不是可清理的临时文件。
- 修改覆盖 `AGENTS.md`、四份事实源、007 规格/验收、Java core/memory/tool/boot 接线；受控 Python 组件大部分仍未跟踪。
- `.verification/` 是忽略目录，保存长日志、扫描、镜像导出及隔离测试状态；换机器/新克隆不会自动带走这些证据。含密钥的状态文件不能放入提交或普通交接包。
- 本次只新增本交接文件；未执行提交、推送、生产启用或数据库清理。

### Java

- `oryxos-memory/src/main/java/com/oryxos/memory/`：`Mem0HttpTransport`、`Mem0Protocol`、`Mem0MemoryStore`、`Mem0Properties`、`Mem0PropertiesConfiguration`、`MemoryOperationException`、`MemoryOutboundGuard`。
- boot 的 `MemoryOutboundConfiguration` 组合 memory guard 与 tool 的 `HttpWhitelistSandbox`，不引入 memory → tool 反向依赖；未选 Mem0 时不消费其凭证或访问远端。
- 保存只派发一次 PUT；响应不确定时按原操作 ID 有界 GET，不重放推理。错误只给固定分类和 UUID，工具层不可当成功字符串。
- 同一快照读核心全量与归档窗口，严格检查身份、revision、分页/计数/有效期；无静默降级、部分核心或缓存冒充成功。
- **不要随手简化 TLS socket 工厂**：实测 JDK 21 在 `URL.openConnection(Proxy.NO_PROXY)` 下仍可能通过 SSL 无参 socket 继承 SOCKS 选择器。现有实现使 JDK 建立 NO_PROXY 普通 socket 后再叠加标准 TLS，拒绝隐式重连，不改全局代理或 trust-all。
- 仅有来源明确的窄范围 SSRF 静态检查处置，不允许转为全局关闭规则。

### Python、容器与 T080

组件根目录：`integrations/mem0-adapter/`，包：`src/oryx_mem0/`。

- 固定 SDK 只在请求级暂存中提炼；PG 五表承载操作、current、versions、calls 和 namespace，不能改为原版 REST 直连。
- Dockerfile 已改为真实 Linux 多阶段构建、hash 锁定依赖安装、`pip check`、源码清单逐项校验；不再复制宿主机 site-packages。运行层非 root，无测试 Secret。
- 初始化新 PG 卷时显式创建 `vector VERSION '0.8.6'`，应用角色非超级用户。
- 首轮真实部署 6 项中 4 失败：embedding 部分 usage 被拒、replayed 固定 false、持久 FAILED 变 500、请求 ID 冲突变 500。T080 已补测试并修复，没有降低断言。
- 当前 usage 逐字段允许 NULL；register 以真实 INSERT 结果返回 replayed；服务返回匹配的持久终态，未知结果使用固定异常类型；冲突 409。
- GET 组合过期操作及调用恢复；PUT 验证完整身份/hash，流式有界读取请求；分页字节预算计入 request_id。

## 4. 最近验证证据：哪些绿灯、哪些不是

以下日志都在 `.verification/007-memory-backends/`；这是既有运行结果，本交接轮没有重跑。

| 层次 | 最近结果 | 证据文件 | 不能替代 |
|---|---|---|---|
| Python unit | 377/377；73 integration 按标记排除，不计通过 | `t080-all-unit.log` | 真实模型/完整集成 |
| 原有真实 PG 集成 | 65/65 | `t080-pg-regression.log` | 完整部署出口检查 |
| 真实 Docker HTTPS/SDK/PG | 8/8，分 6+2 两次 | `t080-deployed.log`、`t080-deployed-recovery.log` | 真实 LLM/embedding |
| Java 定向 | 93/93，含 68 个 Mem0 HTTPS 契约 | `t060-regression.log` | Java → 真实适配器 → 真实模型全链路 |
| Java 快速 verify | BUILD SUCCESS；boot 5/5，报告中静态违例为 0 | `t060-quality-final.log` | **未跑 OWASP，非 R5** |
| 镜像内 SDK 字节 | 149 个 SDK 文件与受控 wheel 一致，仅 FAISS 改动 | `t049-image-sdk-proof.json` | 其他镜像包安全 |
| 完整 Python 依赖审计 | 71 项依赖，原始 1 项已证实回移修复，未处置 0 | `t049-dependency-audit.log` | OS/镜像安全 |
| 镜像扫描组合门禁 | **blocked，退出 1** | `t049-image-audit-gate-full.json`、`.log` | 不得标 T049 完成 |

Docker 用的是合成模型 HTTP 服务，但实际 SDK、HTTPS、PG 和适配器是真的。覆盖了保存/哈希/重启、幂等、ADD/UPDATE/NOOP/RECALL、逐调用审计、失败/冲突、103 核心分页、认证/NUL/大小、GET 恢复、非 root/只读/无默认路由。不能将合成响应称为真实模型效果验证。

保留红灯定位文件：`t049-deployed-red.log`、`t049-archive-diagnostic.log`、`t080-usage-red.log`。快速 verify 中 PMD 6 仍打印 `aktStatus is NULL: maximum Iterations exceeded`，退出 0 不能证明数据流分析覆盖完整；保留为 R5 待核验项。

## 5. 当前两个硬阻塞

### T049：镜像安全

Trivy 0.73.0，漏洞库更新时间 `2026-09-02T20:01:19Z`。扫描本地 Docker tar，关闭遥测，不上传镜像、业务数据或配置。

| 镜像 | 原始按包计发现 | 不同漏洞编号 | CRITICAL / HIGH | 尚未处置 |
|---|---:|---:|---:|---:|
| 适配器 | 143 | 89 | 3 / 15 | **142** |
| PG/pgvector | 421 | 177 | 16 / 60 | **421** |

两镜像的文件及镜像配置 Secret 发现均为 0。数量按包计，不等于同等数量的独立可利用漏洞；适用性需逐项核验。仅 Mem0 CVE-2026-7597 有获准回移与实际镜像源码证据可处置，其余没有豁免。

- 完整原始扫描：`t049-trivy-adapter-full.json`、`t049-trivy-postgres-full.json`（及各自 `.log`）。
- 门禁脚本：`integrations/mem0-adapter/tests/docker/report_image_audit.py`；需来源绑定的 SDK proof、扫描和 DB metadata，不以改版本字符串规避告警。
- 适配器基础层 Debian 13.6/pip、PG 基础层 Debian 12/Perl/libxml/SQLite/zlib/gosu 等需核验；不要误称“PG 服务有 421 个漏洞”。
- 建议下一步评估：在保持已锁定运行版本/算法前提下精简运行镜像、核验发行版补丁、重新锁 digest、回归复扫。**上一轮只是提出该路线，尚未取得用户对具体方案的明确批准；交接不等于批准升级运行版本或风险豁免。**
- 需要调整 Python/PG/SDK 版本、算法、安全准入或范围时，先提出明确方案并同步批准后的 plan；不设全局 ignore、不只过滤高危后宣称无问题。

### T061/T066：真实内网模型

项目根 `.env` 已确认存在 `DEEPSEEK_API_KEY`、`MOONSHOT_API_KEY`、`NVD_API_KEY`，没有将值打印或写进报告。

这些 key **不等于获准的内网 Mem0 全数据路径**。仍缺内网 LLM/embedding 地址、模型名、向量维度、TLS/CA、允许目标、凭证注入方式及审计/存储/日志路径的确认。不得自动拿云 Provider key 跑 Mem0 提炼/embedding，更不能上传业务记忆。

NVD key 可按已有项目安全验证流程仅注入验证进程，不回显，不把含值命令留在日志。真实环境缺失时保持相关任务未完成；可先做不依赖真实模型的诊断与准备，但如需调整 tasks 的前置关系，应明确记录，不能抢先勾选。

## 6. 锁定产物与环境

| 项目 | 当前值 |
|---|---|
| Docker CLI | `D:/Docker/resources/bin/docker.exe`；PATH 可能仍指向旧 CLI |
| 最近成功验证的 Docker | Desktop 4.89.0 / Engine 29.7.2，linux/amd64 |
| Python / uv | 3.12.14 / 0.10.11 |
| Mem0 SDK | 1.0.11+oryx.1，仅获准 FAISS 安全回移 |
| PG / vector | 17.11 / 0.8.6 |
| 实际适配器 tag | `oryxos/mem0-adapter:t049-d15e7d9716a7` |
| OCI digest | `sha256:0defb36946bbf43bf04f6293b8917bbeb3abfba93522fd1c0974c90576fecf01` |
| source-manifest SHA256 | `d15e7d9716a7d804ff0873a9572c3302ceb5adabfe7fea1580d03e4622b9532b` |

更多基础镜像/wheel/lock 摘要以 `integrations/mem0-adapter/build-manifest.json` 为准。其 `built_local` 只表示实际构建并绑定源码，不是运行准入。

构建证据：`t049-image-final-build.log`、`t049-build-final-metadata.json`、`t049-image-final-inspect.json`。Docker 29/containerd 的 image Id 可为 OCI manifest digest，不一定是经典 config digest；生成器已处理并严格核对，不要直接因二者不同手填摘要。

### 隔离测试资源

- 最后测试结束已停止本次所有容器，保留卷、镜像、证据，没有重启电脑。
- 最近成功项目：`oryx007-t049-b1077252d416`；状态目录：`.verification/007-memory-backends/docker-t049-5n18es2x`。
- 最初缺 vector 的失败项目：`oryx007-t049-96276f700454`；状态目录 `docker-t049-q_e4_72r`，不能把它当最新成功部署。
- 状态目录中的 `state.json`、Secret 文件含合成测试凭证，**不得打印、提交或复制进交接正文**。测试 CA 有效期仅 2 天，过期需新建隔离 fixture。
- 测试库 `oryx_mem0_t049_test`，应用角色 `oryx_mem0_app`，标记注释 `ORYXOS_DISPOSABLE_TEST_DATABASE`。测试会在验证库名/注释/角色/版本后重建本测试库 schema；绝不能把 fixture 指向用户数据库。
- 上次 PG 映射 `127.0.0.1:32768`，这是临时端口，重启后须重新查询。APP/模型只在隔离网中；HTTPS 客户端通过容器内执行访问，不能因宿主端口不可达就放宽网络隔离。
- 本机用户 PG 14/5432 未使用、不可清理；以前的 WSL1 `OryxPgTest`/5433 也不是当前测试目标。不要改系统、重装 Docker、删除发行版或执行 down -v/prune。

## 7. 接手后的安全操作入口

先做只读盘点：`git status --short`、核对任务和摘要、通过上述绝对 CLI 查询 Docker 状态。不要为盘点读取 `.env` 的值或输出 `state.json`。

以下是后续经确认继续工作时的入口，不代表交接轮已经执行。工作目录为 `integrations/mem0-adapter/`。

```powershell
$env:PYTHONUTF8 = '1'
$env:PYTHONUNBUFFERED = '1'
uv run --frozen pytest -q -m 'not integration'

# 创建全新的唯一命名隔离部署；输出状态目录需留存，不使用用户 PG。
uv run --frozen python tests/docker/harness.py setup --docker D:/Docker/resources/bin/docker.exe --image oryxos/mem0-adapter:t049-d15e7d9716a7

# 将 setup 生成的实际目录设置为 ORYX_MEM0_DOCKER_TEST_DIR 后再运行：
$env:ORYX_DOCKER = 'D:/Docker/resources/bin/docker.exe'
uv run --frozen pytest -v tests/docker/test_deployed_api.py --tb=short
```

实际目录由 `setup` 返回，不能照抄旧目录假装服务已启动。PG 集成变量和保护检查见 `tests/integration/conftest.py`；只在进程内从该次 fixture 的 Secret 文件注入，不把密码放在命令行。

改运行源码/构建输入后须重新导出、生成、构建并绑定，不能沿用旧镜像绿灯：

```powershell
uv export --project . --frozen --no-dev --no-emit-project --format requirements-txt --output-file build/runtime-requirements.txt
uv run --frozen python scripts/build_manifest.py
```

上述生成器会将 manifest 重置为 `authored_pending_image_build`、digest 为空，这是正确状态。随后取新的源码清单 SHA，传给 `docker buildx build --platform linux/amd64 --provenance=false --build-arg SOURCE_MANIFEST_SHA256=... --metadata-file ... --load -t ... .`。保存实际 `docker image inspect` JSON，再以 `scripts/build_manifest.py --build-metadata ... --image-inspect ...` 绑定。重新构建后须重跑受影响回归、镜像内 SDK proof、完整依赖和镜像扫描。

其他运行提示：JDK 21 的 Windows socket 临时路径曾使用 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=D:/project/AI-Coding/OryxOs/.verification/jdk-sockets`；Maven `.cmd` 参数中的正则 `|` 会触发 shell 问题，优先不含管道字符的窄匹配。最终 `mvn clean verify` 不跳插件，不能用以前的 `dependency-check.skip=true` 快速检查充数。

## 8. 一致性审查待核验点

以下没有在 T080 中完整收口。接手后对照契约、补可复现测试并将确认的缺口追加到 tasks，不能因现有测试全绿就略过；也不能在缺少复现时把所有疑点说成已证实故障。

1. **capabilities 字段缺口**：协议要求构建/SDK版本和固定限制；`runtime.py`/`app.py` 当前固定返回协议/schema/SDK/三能力，尚缺构建版本与固定限制。Java `Mem0Protocol` 也严格锁定现有字段。需同步协议、实现与两侧 fixture，不得仅放松未知字段验证。
2. **RECALL 一致性与相似度**：`storage/queries.py` 的 recall 没有显式只读 REPEATABLE READ；SQL 使用 `GREATEST(0, 1-distance)`，应核对 data-model 对真实余弦分数及负分过滤的要求，补并发与负分用例。
3. **RECALL revision**：`storage/memories.py` 的 `complete_recall` 要求 receipt revision 等于 lease baseline；claim 后 embedding 期间若有并发 SAVE，查询 revision 可能变化。需统一快照语义后修复，不简单删掉 revision 校验。
4. **暂存快照元数据**：`runtime.py` 的 `PgReadonlySnapshot` 当前以 updated_at 同时填 created_at/updated_at，且需核验实际数据库快照 revision 与 RunContext revision 的绑定。已有写 CAS 不能自动证明读侧元数据正确。
5. **提交回包丢失边界**：复核 `MemoryTransactionStore` 对真实 psycopg commit 异常/结果不确定的处理；已有 `_fail` 的 RUNNING 状态保护，不应先认定会覆盖 COMMITTED，但仅 after_commit 注入还不足以穷尽连接丢失行为。
6. **测试网络防护范围**：`tests/conftest.py` 的 `inprocess_asgi` 在 fixture 期间恢复了原 socket 方法，实际范围大于“仅本进程管道”的注释。需收紧并验证，不删除网络断言。真实容器网络隔离证据仍单独成立。
7. **最终静态门禁**：核实 PMD 数据流 warning 的实际覆盖影响，完成未跳过 OWASP 的最终验证；既往退出 0 不是完整 R5。

## 9. 剩余任务与推荐顺序

| 顺序 | 任务 | 完成条件 |
|---|---|---|
| 1 | T049 | 实际协议/PG/构建证据已有，补齐镜像安全处置与所需门禁；不隐瞒 unresolved |
| 2 | T061 | 获准真实内网模型环境，真实 Java Store → adapter → SDK/模型 → PG 的最小冒烟 |
| 3 | T062 | US3 故事级一致性审查、证据收口、本地稳定提交 |
| 4 | T063–T070 | US4 三后端共同契约、6 向切换、审计、真实模型黄金集、部署 README/quickstart、故事审查与提交 |
| 5 | T071–T072 | CI 外部组件门禁、最终源码/锁图/镜像绑定与完整扫描 |
| 6 | T073–T074 | 全量回归及最终实现一致性审查，发现缺口追加补救任务 |
| 7 | T075 | 仅 R1–R5 全通过才归档；只提交明确属于本 feature 的变更，不自动 push |

用户已经明确让 Kimi 接手。历史中的“主模型执行、不调度 Spark”表示不要再次把编码/测试交给 Spark；本次交接没有授权再分派其他 Agent。仍保持九模块、核心公开端口、默认 Markdown、本地后端原文/回归与数据不出域约束；不修改宪法，不通过假 Store 或删除断言将未验收项标绿。

若只能完成部分工作，应如实报告当前阻塞与证据，而不是把“代码已写/测试已绿”当作“007已完成”。
