# 007 镜像安全复核（2026-09-03 第二轮）

结论：**R1 镜像安全门禁已通过（passed，退出0）**，T049 可以勾选。R4（真实模型）与 R5（全仓封板）仍未通过，Mem0 仍不可启用，007 不能归档为完成。

## 第二轮处置路径（经用户 2026-09-03 明确批准）

用户批准了两项政策：

1. **逐项适用性评估处置**：每条发现给出带证据的处置（已修复/已移除/发行版评估/配置不适用），原始扫描完整保留，不设全局忽略，门禁仍要求 0 未处置。
2. **Debian 锁定版本 + 精简运行层**：Python 3.12.14 / PG 17.11 / pgvector 0.8.6 版本不变。

实际执行的三项镜像变更：

| 变更 | 效果 |
|---|---|
| 适配器运行层移除 pip/ensurepip（依赖在构建层锁定校验） | pip 25.0.1 的 6 条发现消除 |
| PG 基础镜像 bookworm → 官方 `0.8.6-pg17-trixie`（PG 17.11/pgvector 0.8.6 不变），派生层 hold postgresql*/libpq* 后应用发行版已发布修复 | 93 条有已发布修复的发现消除；trixie 上其余发现均有发行版评估记录 |
| 派生 PG 镜像移除 gosu、容器全程以 postgres(999) 运行（无 root 阶段、无 cap_add） | gosu 内嵌旧 Go stdlib 的 46 条发现消除 |

## 当前证据

| 对象 | 按包计发现 | 已验证回移修复 | 逐项处置 | 未处置 | Secret |
|---|---:|---:|---:|---:|---:|
| 适配器镜像 `sha256:e811033d…` | 173 | 1（Mem0 CVE-2026-7597） | 172 | **0** | 0 |
| PG 派生镜像 `sha256:218ac197…` | 321 | 0 | 321 | **0** | 0 |

- 处置台账：`integrations/mem0-adapter/tests/docker/image-finding-dispositions.json`，493 条（适配器 172 + PG 321），逐条绑定 镜像/CVE/包/安装版本，含理由、来源链接与复核日期。
- 处置构成分布：适配器 172 = 49 unimportant + 123 no-DSA；PG 321 = 101 unimportant + 220 no-DSA。全部附 Debian 安全跟踪器来源；HIGH/CRITICAL 项另附配置级证据（perl/sqlite/ncurses/systemd/gzip/acl/gnupg 组件不在容器运行路径、CVE-2026-8376 仅限 32 位、CVE-2026-6653 安装版本不在受影响范围）。
- 门禁：`report_image_audit.py --dispositions` 要求每条发现都有匹配台账记录、版本一致、无过期记录，否则失败；结果 `t049e-image-audit-gate-full.json` 状态 passed、退出 0。
- 扫描：Trivy 0.73.0，漏洞库 UpdatedAt 2026-09-03T07:08:48Z（`t049c-trivy-db-metadata.json`），本地 tar 离线扫描，遥测关闭，未上传镜像或业务数据。
- 漏洞库在两次扫描间从 2026-09-02T20:01Z 更新到 2026-09-03T07:08Z，新增 util-linux 4 个新 CVE；同一批次双镜像用同一库扫描，结果自洽。第一轮计数（适配器 143 / PG 421）不可与本轮直接比较。

## 回归证据（最终镜像）

- Python unit 377/377（`t049e-unit.log`）；真实容器部署 8/8（`t049e-deployed.log`）；真实 PG 集成 65/65（`t049e-pg-regression.log`）；镜像内 SDK 字节 proof（`t049e-image-sdk-proof.json`，149 文件仅 FAISS 改动）。
- PG 版本核验：加固后 `psql 17.11 (Debian 17.11-1.pgdg13+2)`、vector 扩展 0.8.6、harness 启动断言通过；`postgresql-17` 处于 apt hold，未被升级。
- 所有本轮测试容器已停止，卷/镜像/证据保留。

## 可复核文件（`.verification/007-memory-backends/`）

- `t049e-trivy-adapter-full.json` / `t049e-trivy-postgres-full.json`：最终完整原始扫描。
- `t049e-image-audit-gate-full.json` / `.log`：组合门禁（passed）。
- `t049e-image-sdk-proof.json`：镜像内 SDK 字节证明。
- `t049e-pg-build.log`、`t049e-*-build-metadata.json`、`t049e-*-image-inspect.json`：构建与绑定证据。
- `t049c-debian-tracker.json`：处置评估使用的 Debian 安全跟踪器全量快照（2026-09-03T12:01Z）。
- 第一轮证据（`t049-*`、`t049c-*`、`t049d-*`）全部保留，含 blocked 门禁与红灯日志。

## 仍需注意

- 台账处置依据是 Debian 安全团队的 no-DSA/unimportant 评估与配置级不可达证据；上游若未来改变评估（撤销 no-DSA），复扫时对应发现会重新出现并阻塞门禁，届时须重新处置。
- `apt-get upgrade` 在派生层应用的是构建时的发行版修复；重建镜像会拉取彼时最新修复并改变 digest，需重新绑定 manifest 与复扫。
- 真实内网模型（R4/T061/T066）与全仓封板（R5/T071–T075）不在本轮范围。
