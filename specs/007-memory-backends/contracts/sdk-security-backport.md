# 007 SDK 安全回移契约

2026-08-31用户批准：保留原提炼算法，回移官方安全补丁，重新核验依赖锁与安全扫描后继续；随后确认所有回归由主模型执行，不再调度Spark。不修改宪法或九模块边界。

## 来源与构建

- 基线仍为Mem0 1.0.11 / Git `144627c4ce5bc4db6acac17cbd158065f2b27a8d`。官方PyPI wheel的149个SDK Python文件已与此Git树逐项匹配，SHA256为 `bcf4d678dc0a4d4e8eccaebe05562eae022fcdc825a0e3095d02f28cf61a5b6d`。
- 仅回移官方 `62dca096f9236010ca15fea9ba369ba740b86b7a` 中针对 `mem0/vector_stores/faiss.py` 的补丁：受限旧pickle读取、结构验证和JSON保存。不得升级或改写memory/main.py、提示词、提炼/UPDATE/DELETE/NONE算法。
- 受控包使用诚实的本地版本 `1.0.11+oryx.1`，不冒充官方已修复的2.x。源wheel、Git文件清单、补丁、产物wheel分别锁摘要；依赖指向本地不可编辑wheel。所有SDK源码只允许FAISS文件变化，元数据与RECORD按wheel规范重建。
- `integrations/mem0-adapter/vendor/upstream/`保留官方构建输入，`patches/CVE-2026-7597.patch`保留原始补丁；第三方许可证与原文保留。`vendor/sdk-lock.json`、`vendor/upstream-sdk-files.json`记录完整来源与摘要，`scripts/sdk_build.py`无网络、严格校验输入后确定性重打包，不能修改.venv后假称可重复构建。
- 输入源码只用于构建/验证，不进入运行镜像；FAISS仍不安装、不注册为本后端。修复其代码不等于扩大运行存储范围。

## 测试与扫描处置

- `tests/unit/test_sdk_security.py`通过实际补丁后的FAISS Python模块验证危险pickle拒绝、合法旧数据读取/JSON迁移、JSON优先及保存不写pickle；仅原生向量文件I/O用测试替身，不替换反序列化实现。合成攻击只写隔离临时标记，不能执行系统命令。
- `test_sdk_build.py`验证可重复构建、输入/补丁篡改拒绝、149文件集合和非FAISS源码完全不变、实际安装与wheel内容一致。任何不匹配都阻断。
- `scripts/audit_dependencies.py`先验证安装与受控构建、运行补丁回归，再扫描完整安装依赖。仅在来源证明成立后把本地版本映射回上游1.0.11查询漏洞库，防止利用本地版本让扫描器跳过包。
- 原始pip-audit JSON、退出码和全部发现保留，不使用 `--ignore-vuln` 或排除整个包。只有固定mem0ai基线的 `PYSEC-2026-2636` / `CVE-2026-7597` / `GHSA-xqxw-r767-67m7` 且补丁与回归证明成立，才在独立处置报告标为“本构建已修复”。这是有证据的补丁处置，不是未修复风险豁免。
- 新CVE、新别名、其他包、缺失/重复/跳过依赖、扫描失败、证明或回归失败均保持非零。原始版本扫描仍可能命中1项，不能对外称其零告警；只有“未处置发现为0”的组合门禁可通过。未来SDK/补丁/依赖变化须重新验证，不能继承旧报告。
- 对应新增T076–T079，先于T025–T030执行。通过本补丁门禁仍不代表暂存、PG事务、真实模型、镜像或007封板验收通过。
