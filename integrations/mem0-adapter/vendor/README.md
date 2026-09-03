# 受控 Mem0 安全构建

基线为官方Mem0 1.0.11，Git `144627c4ce5bc4db6acac17cbd158065f2b27a8d`。`upstream/`是构建/测试输入，不是运行依赖，已从Docker上下文排除。原始wheel内保留上游许可证；上游Python文件与diff保持原文，以便逐字核验。

运行依赖固定为 `mem0ai-1.0.11+oryx.1-py3-none-any.whl`。仅回移官方提交 `62dca096f9236010ca15fea9ba369ba740b86b7a` 的FAISS安全节点，保留1.0.11的`limit`接口、提炼算法以及全部非FAISS源码，不启用FAISS后端。

从仓库根目录验证：

```powershell
uv sync --frozen --project integrations/mem0-adapter
uv run --frozen --directory integrations/mem0-adapter python scripts/sdk_build.py --check-installed
uv run --frozen --directory integrations/mem0-adapter pytest -q
uv run --frozen --directory integrations/mem0-adapter python scripts/audit_dependencies.py
```

`sdk_build.py --write`从固定输入离线重建wheel；时间、顺序和权限固定，使用不依赖zlib版本的ZIP存储格式。`sdk-lock.json`锁定输入、适配diff和产物SHA256，`upstream-sdk-files.json`记录149个官方Git源码blob；任何变更都必须重新评审和验收。

审计先校验安装来源、依赖锁与补丁回归，再查询全部安装依赖。受控本地版本按其真实上游版本查询，避免扫描器因PyPI不存在本地版本而跳过。原始报告和退出码不会改写；只有本构建中已证实修复的目标CVE被单独标为已修复，其他发现、来源/版本漂移、缺失或跳过包均阻断。报告存入每次独立的`.verification/007-memory-backends/dependency-audit/`子目录。

此证明只覆盖SDK补丁，不代替暂存机制、真实PG、内部模型、网络出口或镜像验收。当前组件尚未提供可启用的Mem0业务服务。
