# `.kimi-code/` — Kimi Code 项目级目录

OryxOS 仓库里 **Kimi Code CLI** 的项目级配置目录。与 `.claude/`、`.codex/`、`.cursor/` 并列，是跨工具 Agent 设置的第四个入口。

## 布局

```
.kimi-code/
  README.md     # 本说明
  mcp.json      # 项目级 MCP server 声明（可选，随仓库共享）
  local.toml    # 本机专属设置（/add-dir 记住的附加工作区，勿提交，见 .gitignore）
```

## 与 `.agents/` 单一事实来源的关系

Kimi Code **原生读取** `AGENTS.md` 和 `.agents/skills/`，无需任何软链：

| 内容 | Kimi Code 读取位置 |
|------|-------------------|
| 统一指令 | `AGENTS.md`（仓库根，原生支持） |
| Skills | `.agents/skills/`（原生支持，`merge_all_available_skills` 默认开启） |

因此本目录**只放 Kimi Code 私有配置**（MCP、本机工作区），不要在这里复制指令或 skills。

## 维护约定

1. 指令与 skills 一律改 `AGENTS.md` 和 `.agents/skills/`，Kimi Code 自动生效。
2. `local.toml` 存绝对路径、因机器而异，已加入 `.gitignore`，不要提交。
3. 项目级 MCP server 声明放 `mcp.json`（与用户级 `~/.kimi-code/mcp.json` 启动时合并）。
