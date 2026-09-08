---
name: oryxos-admin-ui
description: >-
  生成或扩展 OryxOS 管理台（/admin）页面：Vue 3 + Vite 单页应用，视觉钉死官网首页设计 token
  （深色 + 橙色强调、Inter/JetBrains Mono），默认只读、支持已批准的定时管理操作、只调 /api/v1。当用户要求"做管理台页面 /
  给管理台加页 / 改管理台样式 / admin console / 管理平台 UI"时使用。
---

# OryxOS Admin UI

生成/扩展 OryxOS 管理台时**必须**遵循本规范：视觉与工程约定全部钉死，不自由发挥。
产出前的自检清单见文末「验收清单」，逐条过了才算完成。

## 适用范围

- 管理台源码工程：`oryxos-web/src/main/frontend/`（Vue 3 + Vite）。
- 构建产物：`oryxos-web/src/main/resources/static/admin/`，由 Spring 托管在 `/admin`。
- 设计 token 的唯一事实来源是 `website/.vitepress/theme/custom.css`（官网首页）。
  本文件的值抄自该文件；若该文件更新，以该文件为准并回来同步本 skill。

## 设计 Token（抄自 website/.vitepress/theme/custom.css，一个字别自创）

| 用途 | 值 |
|---|---|
| 页面背景 | `#0a0a0a` |
| 卡片/悬浮块背景 | `#171717` / `#262626` |
| 分隔线 / 边框 | `#262626` / `#404040` |
| 主色（橙，仅强调用） | `#fb923c`（亮）/ `#f97316`（主）/ `#ea580c`（深）；软底色 `rgba(249,115,22,0.1)` |
| 文字 主/次/弱 | `#ffffff` / `#d4d4d4` / `#a3a3a3` |
| 正文字体 | `'Inter', 'SF Pro Display', -apple-system, sans-serif` |
| 等宽字体（代码/ID/JSON） | `'JetBrains Mono', 'Fira Code', ui-monospace, monospace` |
| 圆角 | 卡片/表格 12px；按钮/标签 6–8px |
| 状态色 | 成功 绿（如 `#22c55e`）、失败 红（如 `#ef4444`）、警告 橙（用主色 `#f97316`），以小圆点/标签呈现 |

约束：主色只用于强调（激活导航、链接、数值高亮、hover），不铺大面积背景；整体克制、留白足。

## 工程约定（不得偏离）

- 技术栈：Vue 3 + Vite + vue-router；不引 UI 组件库（token 全覆盖，自写样式）。
- `vite.config`：`base: '/admin/'`；`build.outDir` 指向 `../resources/static/admin` 且
  `emptyOutDir: true`；dev server 配 `server.proxy` 把 `/api` 代理到 `localhost:8080`。
- 所有数据请求只打 `/api/v1/**`（同源，不硬编码 host）；统一 fetch 封装：解成功信封
  `{code,message,data,timestamp}`（取 `data`）；非 2xx 读错误信封 `{errorCode,message,timestamp}`
  并把 `message` 抛给页面显示。
- 已交付五页保持只读。2026-09-07 用户批准 011 的定时页例外：允许立即执行、启用/停用；不扩展成任务定义 CRUD。依据 `docs/decisions/028-scheduler-subsystem-preflight.md`。对应真实端点完成后才接按钮，不做假数据；请求期间禁止重复提交，失败明确展示，不自动重放立即执行。
- SPA 路由用 history 模式；Spring 侧已对 `/admin/**` 未命中路径回落 `index.html`，子路由刷新不 404。
- 顶栏放 `/admin/logo.svg`（工程 `public/logo.svg`，源自 `website/public/logo.svg`）+ 文字「OryxOS 管理台」。

## 布局与交互规范

- 左侧竖直深色导航 + 右侧内容区；导航激活项：主色文字 + 软橙底（`rgba(249,115,22,0.1)`）。
- 表格：深色底（行 `#0a0a0a`、表头 `#171717`），行分隔 `1px solid #262626`，hover 行
  `rgba(249,115,22,0.05)`；ID/时间/JSON 列用等宽字体。
- 每个数据页都必须有三态：**加载中**（骨架或 spinner 文案）、**空数据**（明确占位文案，不白屏）、
  **错误**（显示错误信封的 `message` + 重试入口）。
- 响应式：窄屏（<768px）导航收起为顶部汉堡/抽屉。

## 当前页面 ↔ 端点映射

| 页面 | 路由 | 端点 |
|---|---|---|
| 会话列表 | `/sessions` | `GET /api/v1/sessions?page=&size=`（分页表格：id/profile/channel/status/lastActiveAt） |
| Profile 列表 | `/profiles` | `GET /api/v1/profiles` |
| Tool 列表 | `/tools` | `GET /api/v1/tools` |
| 长期记忆 | `/memory` | `GET /api/v1/memory`（`content` 用等宽 `<pre>` 呈现） |
| 运行状态 | `/status` | `GET /api/v1/info` + `GET /api/v1/health` |
| 定时任务 | `/schedules` | `GET /api/v1/schedules`、`GET /api/v1/schedules/{id}/executions`、`POST /api/v1/schedules/{id}/run`、`PUT /api/v1/schedules/{id}` |

**唯一写操作例外（011 范围批准）**：定时任务页允许行级"立即执行"（POST run）与"启停"（PUT enabled）两个操作；其余页面保持整站只读。该页必须遵守：行级 submitting 防重复点击；操作成功后刷新列表与历史，不只乐观改 UI；业务失败（200 + success=false）显示失败结果，不显示"执行成功"；超时/失联显示"执行结果需通过历史确认"，不自动重发 POST。
| 定时任务（011 计划，未交付） | `/schedules` | `GET /api/v1/schedules`、`GET /api/v1/schedules/{id}/executions`、`POST /api/v1/schedules/{id}/run`、`PUT /api/v1/schedules/{id}` |

新增页面时：路由挂进导航，端点必须已存在于 `/api/v1`（缺端点先停下报告，不造数据）。

## 验收清单（生成/改动后逐条自检）

- [ ] token 值与本文件表格一致（无自创色值/字体/圆角）
- [ ] `base: '/admin/'` 且产物落 `static/admin/`；`npm ci && npm run build` 一次通过
- [ ] 原五页三态齐备且只读；011 定时页具备三态、真实执行/启停操作及失败反馈，无额外写入口
- [ ] 窄屏导航可收起；表格/字体/边框与官网气质一致
- [ ] 子路由（如 `/admin/sessions`）刷新由 SPA 回落承载，不 404
- [ ] 所有请求只打 `/api/v1/**`，错误页显示错误信封 message
