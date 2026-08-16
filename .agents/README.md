# `.agents/` — 跨工具 Agent 标准目录

本目录是 OryxOS 仓库里 **Claude Code / Codex / Cursor / Kimi Code** 共用的 Agent 配置与 Skills 单一事实来源。

## 布局

```
AGENTS.md                 # 统一指令（Codex / Kimi Code / 多数工具原生读取）
CLAUDE.md       → 软链 → AGENTS.md          # Claude Code
.agents/
  README.md               # 本说明
  skills/                 # Agent Skills（agentskills.io / SKILL.md）
    layered-arch-diagram/
.codex/
  config.toml             # Codex 项目级配置（无软链，Codex 原生读 AGENTS.md）
.claude/skills  → 软链 → .agents/skills     # Claude Code skills
.cursor/skills  → 软链 → .agents/skills     # Cursor skills
.cursor/rules/oryxos.mdc  # Cursor 规则入口（指向 AGENTS.md）
.kimi-code/               # Kimi Code 项目级配置（无软链，原生读 AGENTS.md 与 .agents/skills）
```

| 工具 | 指令文件 | Skills 路径 |
|------|----------|-------------|
| **Codex** | `AGENTS.md` | `.agents/skills/`（原生读取） |
| **Claude Code** | `CLAUDE.md`（→ `AGENTS.md`） | `.claude/skills/`（→ `.agents/skills/`） |
| **Cursor** | `AGENTS.md` + `.cursor/rules/` | `.cursor/skills/`（→ `.agents/skills/`） |
| **Kimi Code** | `AGENTS.md`（原生读取） | `.agents/skills/`（原生读取） |

## 维护约定

1. **只改 `AGENTS.md` 和 `.agents/skills/`**，不要直接改软链目标另一侧的副本。
2. 新增 skill：放到 `.agents/skills/<name>/SKILL.md`，四个工具会自动看到。
3. 克隆仓库后若软链丢失（常见于未开 Windows Developer Mode / 未开 `core.symlinks`），运行：

```powershell
pwsh -File scripts/link-agents.ps1
```

4. 业务事实来源仍是 `docs/` 四份文档；`AGENTS.md` 只提炼可执行约束。

## 已收录 Skills

| Skill | 用途 |
|-------|------|
| `layered-arch-diagram` | 彩色分层架构图（Channel → Core → Capability → Storage） |
