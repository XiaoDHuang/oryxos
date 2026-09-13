# Contract: Agent 目录格式（作者面契约）

本契约定义"一个目录 = 一个 Agent"的作者格式。它是业务方唯一能见的面，字面量逐字保真，不得擅改。

## 目录布局

```text
.oryxos/agents/<name>/        # 一个子目录 = 一个 Agent；<name> 即 Agent 名
├── AGENT.md                  # 必备主文件：frontmatter（运行配置）+ 正文（任务指令）
├── REFERENCE.md              # 可选参考：拿不准才经底座 read_file 读
├── skills/                   # 可选子指令目录：*.md，用到才经 read_file 读
└── scripts/                  # 可选脚本目录：用到才经底座 shell 跑，产出进上下文、代码不进
```

## AGENT.md 语法

```markdown
---
name: daily-reconcile              # 必填，唯一标识
description: 一句话说明
identity:
  agent_name: 对账小欧              # 可选，展示名
  prompt: 人格/行事准则（内联进 system prompt 首段）
provider:                           # 必填块；name 必填且须在全局 provider 层声明
  name: deepseek
  model: deepseek-chat
  temperature: 0.2
tools: [shell, read_file, notify, save_memory]   # 可选；未注册能力名 → 加载告警
notify_channels:                    # 可选，NotifyTools 消费
  - type: webhook
    url: ${OPS_WEBHOOK_URL}         # 敏感值必须 ${ENV_VAR} 占位
schedules:                          # 可选；声明即到点自动跑
  - {id: reconcile-morning, cron: "0 0 9 * * *", zone: Asia/Shanghai, message: 触发消息}
---

正文：给模型的任务指令。被触发时进 system prompt；每次触发从磁盘现读，改完即时生效。
frontmatter 键集与手写 Profile YAML 完全同构（snake_case），未知键忽略。
```

## 加载语义（错误与告警）

| 情况 | 行为 |
|---|---|
| agents/ 目录不存在或为空 | 正常启动，注册数为零 |
| 子目录缺 `AGENT.md` | 记错误日志点名目录，跳过，不阻断其余 |
| frontmatter 坏 YAML / 缺 `---` 围栏 | 记错误日志点名，跳过 |
| 缺 `name` / `provider.name` 未在全局声明 | 校验失败：启动路径记错误日志并跳过；运行时注册路径抛 `IllegalArgumentException`，**同一异常类型、同一消息** |
| `tools` 含未注册能力名 | warn 日志点名能力名与 Agent 名，不阻断 |
| 与既有注册项同名 | 记错误日志点名，跳过派生注册，不覆盖（同名策略属扩展阶段） |
| `schedules` 条目非法（缺要素/cron/时区/id 重复） | 记错误日志跳过该条，Agent 本身仍注册（011 既有规则） |

## 资源加载语义（渐进式披露）

- 正文：常驻 system prompt（触发时现读，无缓存）。
- `REFERENCE.md`、`skills/*.md`：不预载；模型按正文指引用底座 `read_file` 读入。
- `scripts/*`：不预载；模型按正文指引用底座 `shell` 跑；**只有脚本产出进上下文，代码不进**。

## 脚本信任边界（如实声明）

脚本是任意代码：`python scripts/foo.py` 一旦经解释器白名单放行，其内部可读写文件、**可自发网络请求**——绕过 `http_get` 的域名白名单（白名单只管内置工具，管不到子进程网络）。核心阶段沙箱对脚本只有"解释器（python/bash 等经 `shell.allowed_commands`）+ 限定本 Agent `scripts/` 目录"两道防线。**安装一个带脚本的 Agent = 信任写它的人**；第三方 Agent 的容器/网络隔离属扩展阶段。
