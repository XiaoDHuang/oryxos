# `.codex/` — Codex 项目级目录

Codex **原生读取**本仓库根目录的：

- `AGENTS.md` —— 统一 Agent 指令（单一事实来源）
- `.agents/skills/` —— Agent Skills

因此本目录**不需要软链**，只放 Codex 专属的项目级内容：

```
.codex/
  config.toml   # 项目级 Codex 运行参数（推理强度、网络、上下文窗口等）
  prompts/      # 自定义 prompt（可选，按需添加）
```

## 维护约定

1. 指令与 Skills 一律改 `AGENTS.md` 和 `.agents/skills/`，不要在本目录另存副本。
2. 跨工具布局总览见 [`.agents/README.md`](../.agents/README.md)。
