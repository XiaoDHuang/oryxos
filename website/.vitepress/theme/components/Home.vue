<script setup>
import { computed } from 'vue'
import { useData } from 'vitepress'

const { lang } = useData()
const isZh = computed(() => lang.value === 'zh-CN')
const t = (zh, en) => isZh.value ? zh : en

const capabilities = computed(() => [
  {
    icon: '🤖',
    title: t('对接 LLM', 'LLM Providers'),
    subtitle: t('Spring AI Alibaba · 显式 name 映射 · 无 lock-in', 'Spring AI Alibaba · explicit name mapping · no lock-in'),
    code: `# Profile 引用 Provider
name: ops-assistant
provider:
  name: deepseek
  model: deepseek-chat
  temperature: 0.7

# 运行时切换不锁定
oryxos chat --profile ops-assistant

# DeepSeek / Kimi / Qwen / Zhipu
# OpenAI 兼容协议即插即用`,
  },
  {
    icon: '🧠',
    title: t('ReAct 循环', 'ReAct Loop'),
    subtitle: t('自实现引擎 · 数十行核心代码 · 完全可控', 'Self-built engine · ~50 lines core · fully controllable'),
    code: `while iteration < maxIterations:
    prompt = PromptBuilder
        .build(system, memory, history, tools)
    response = provider.chat(prompt)

    if response.hasToolCalls():
        results = toolExecutor.execute(response.tools)
        session.append(results)
        continue

    return response.content`,
  },
  {
    icon: '📚',
    title: t('Memory 三层记忆', 'Three-Layer Memory'),
    subtitle: t('会话记忆 · MEMORY.md 长期记忆 · 关键词检索', 'Session memory · MEMORY.md · keyword recall'),
    code: `# Agent 主动写入长期记忆
save_memory("用户项目用 Spring Boot，
           部署在 K8s 上")

# 关键词检索
recall_memory("数据库")

# 启动时整个 MEMORY.md
# 注入 system prompt`,
  },
  {
    icon: '🔧',
    title: t('Tool 体系', 'Tool System'),
    subtitle: t('内置 File / Shell / HTTP · MCP · @Tool 注解', 'Built-in File/Shell/HTTP · MCP · @Tool annotation'),
    code: `# 内置工具开箱即用
read_file, write_file, list_dir
shell, http_get, http_post
save_memory, recall_memory

# 零代码：SKILL.md + MCP
# 轻代码：自写 MCP server
# 重代码：@Tool Spring Bean`,
  },
  {
    icon: '🌐',
    title: t('Web Service', 'Web Service'),
    subtitle: t('10 个核心 REST 端点 · 业务系统唯一入口', '10 core REST endpoints · single integration entry'),
    code: `POST /api/v1/sessions
POST /api/v1/sessions/{id}/messages
GET  /api/v1/sessions/{id}
DELETE /api/v1/sessions/{id}
POST /api/v1/agents/{name}/invoke
GET  /api/v1/profiles
GET  /api/v1/memory
GET  /api/v1/tools
GET  /api/v1/health
GET  /api/v1/info`,
  },
])

const scenarios = computed(() => [
  {
    num: '01',
    title: t('运维助手', 'Ops Assistant'),
    desc: t('告警通过 Webhook 触发 Agent，自动拉日志、交叉引用历史故障、执行自愈脚本，在企业微信群里汇报结果。', 'Webhooks trigger the agent — pull logs, cross-reference past incidents, run mitigation, report to the team chat.'),
  },
  {
    num: '02',
    title: t('知识管理助手', 'Knowledge Assistant'),
    desc: t('索引内部合同模板、法规文档、历史案例，员工提问时检索 Memory 并给出带引用来源的建议草稿。', 'Index contracts, regulations and cases. Answers cite sources from long-term memory for compliance traceability.'),
  },
  {
    num: '03',
    title: t('销售助手', 'Sales Assistant'),
    desc: t('拜访前自动拉取 CRM 交易记录、企查查工商信息、客户决策人画像，一分钟生成客户简报。', 'Before a visit, pull CRM history, business registry info and decision-maker profiles into a one-minute brief.'),
  },
  {
    num: '04',
    title: t('客服助手', 'Customer Service'),
    desc: t('LLM 理解用户问题，ReAct 循环调知识库 Tool，Memory 记住客户历史，通过 Web Service 嵌入客服系统。', 'Understand queries, call knowledge tools via ReAct, remember customer history, integrate through REST API.'),
  },
  {
    num: '05',
    title: t('研发助手', 'Dev Assistant'),
    desc: t('接入 GitHub、Jira、CI 系统，理解需求后自动读代码、改代码、跑测试，Memory 记住项目惯例。', 'Connect GitHub/Jira/CI. Read and modify code, run tests, remember project conventions across sessions.'),
  },
  {
    num: '06',
    title: t('数据分析助手', 'Data Analysis'),
    desc: t('自然语言生成 SQL，ReAct 循环执行查询并生成图表，Plugin Tool 对接 BI 系统，让 BI 工具支持自然语言查询。', 'Generate SQL from natural language, execute and chart results via ReAct, integrate with BI through plugins.'),
  },
])

const integrations = computed(() => [
  {
    icon: '💻',
    title: t('CLI 交互', 'CLI Interaction'),
    desc: t('oryxos chat 交互式多轮对话，oryxos serve 启动服务，12 个命令覆盖工作区初始化、Profile 管理、状态查询。', 'Interactive chat, serve mode, 12 commands covering workspace init, profile management and status checks.'),
    code: 'oryxos init\noryxos chat --profile default\noryxos serve --port 8080',
  },
  {
    icon: '🔌',
    title: t('REST API 集成', 'REST API Integration'),
    desc: t('业务系统通过标准 HTTP 调用 OryxOS，任何语言都能接入。同步调用、会话保持、Webhook 触发全覆盖。', 'Any business system integrates via HTTP. Sync calls, session retention and webhook triggers out of the box.'),
    code: 'curl -X POST localhost:8080/api/v1/sessions \\\n  -H "Content-Type: application/json" \\\n  -d \'{"profile":"default"}\'',
  },
  {
    icon: '🧩',
    title: t('Plugin Tool / MCP', 'Plugin Tool / MCP'),
    desc: t('写 SKILL.md 描述意图，复用社区 MCP server 零代码上线新场景；自写 MCP server 或 @Tool 注解做深度集成。', 'Write SKILL.md to describe intent, reuse community MCP servers for zero-code scenarios, or deep-integrate with @Tool.'),
    code: '# .oryxos/skills/daily-pr-digest.md\n# + mcp_servers.yaml\n# 零代码上线新场景',
  },
])

const commands = computed(() => [
  { cmd: 'oryxos init', desc: t('初始化 .oryxos/ 工作区', 'Initialize .oryxos/ workspace') },
  { cmd: 'oryxos status', desc: t('查看配置和运行状态', 'Check config and runtime status') },
  { cmd: 'oryxos chat', desc: t('交互式多轮对话', 'Interactive multi-turn chat') },
  { cmd: 'oryxos serve', desc: t('启动 HTTP API 服务', 'Start HTTP API service') },
  { cmd: 'oryxos gateway', desc: t('启动多渠道守护进程', 'Start multi-channel daemon') },
  { cmd: 'oryxos profile list', desc: t('列出所有 Profile', 'List all profiles') },
  { cmd: 'oryxos profile create', desc: t('创建新 Profile', 'Create a new profile') },
  { cmd: 'oryxos profile show', desc: t('查看 Profile 详情', 'Show profile details') },
  { cmd: 'oryxos profile delete', desc: t('删除 Profile', 'Delete a profile') },
  { cmd: 'oryxos provider list', desc: t('列出已配置 Provider', 'List configured providers') },
  { cmd: 'oryxos tool list', desc: t('列出已注册 Tool', 'List registered tools') },
  { cmd: 'oryxos session list', desc: t('列出会话历史', 'List session history') },
])
</script>

<template>
  <div class="oryxos-page">

    <!-- ── HERO ── -->
    <section class="oryxos-hero">
      <div class="oryxos-hero-bg">
        <div class="oryxos-grid"></div>
        <div class="oryxos-orb oryxos-orb-1"></div>
        <div class="oryxos-orb oryxos-orb-2"></div>
      </div>

      <div class="oryxos-hero-inner">
        <div class="oryxos-badge">
          <span class="oryxos-badge-dot"></span>
          {{ t('企业级 Agent OS', 'Enterprise Agent OS') }}
        </div>

        <h1 class="oryxos-title">
          <span class="oryxos-title-name">OryxOS</span>
        </h1>

        <p class="oryxos-title-sub">{{ t('Java 原生的 Agent 统一底座', 'Java-native Agent Foundation') }}</p>

        <p class="oryxos-hero-desc">
          {{ t('企业能完全掌控的、Java 原生的、私有可审计的 Agent OS。装在你自己的 K8s 或服务器上，作为统一底座跑起运维助手、客服助手、HR 助手、销售助手、知识管理助手……数据不出企业，模型不锁生态。', 'A Java-native, private, auditable Agent OS you fully control. Run ops assistants, customer service agents, HR agents and knowledge assistants on your own Kubernetes or servers. Data stays inside your infrastructure, no cloud lock-in.') }}
        </p>

        <div class="oryxos-hero-actions">
          <a class="oryxos-btn-primary" :href="t('/docs/quick-start', '/en/docs/quick-start')">
            {{ t('快速开始', 'Get Started') }}
            <span class="oryxos-btn-arrow">→</span>
          </a>
          <a class="oryxos-btn-ghost" :href="t('/docs/what', '/en/docs/what')">
            {{ t('了解 OryxOS', 'What is OryxOS') }}
          </a>
          <a class="oryxos-btn-ghost" href="https://github.com/XiaoDHuang/oryxos" target="_blank" rel="noopener">
            GitHub
          </a>
        </div>

        <div class="oryxos-hero-note">
          <span class="oryxos-tech-tag">JDK 21</span>
          <span class="oryxos-tech-tag">Spring Boot 3.x</span>
          <span class="oryxos-tech-tag">Spring AI Alibaba</span>
          <span class="oryxos-tech-tag">SQLite</span>
          <span class="oryxos-tech-tag">MCP</span>
        </div>
      </div>
    </section>

    <!-- ── PROBLEM ── -->
    <section class="oryxos-section oryxos-problem-section">
      <div class="oryxos-section-inner">
        <div class="oryxos-problem">
          <div class="oryxos-problem-text">
            <h2 class="oryxos-section-title">{{ t('企业落地 Agent 的两个痛点', 'Two Blockers for Enterprise Agents') }}</h2>
            <p>{{ t('企业在生产环境跑 Agent，真正卡住的往往不是模型能力，而是基础设施。', 'What blocks production agents is rarely model capability — it is infrastructure.') }}</p>
            <p class="oryxos-problem-item">
              <strong>{{ t('① 数据与审计不可控', '① Data & audit out of control') }}</strong>
              {{ t('核心业务数据不能出企业，系统必须完全可审计，任何新组件都要过现有安全合规流程。', 'Core business data cannot leave the enterprise. Systems must be fully auditable and pass existing security compliance.') }}
            </p>
            <p class="oryxos-problem-item">
              <strong>{{ t('② 技术栈割裂', '② Tech stack fragmentation') }}</strong>
              {{ t('企业后端是 Java/Spring，运维监控是 Nacos、Sentinel、SkyWalking，而 Agent 底座却是 Node.js 或 Python。', 'Enterprise backends are Java/Spring with Nacos/Sentinel/SkyWalking, but agent runtimes are Node.js or Python.') }}
            </p>
            <p class="oryxos-solution-line">{{ t('OryxOS 用 Java 原生实现 Agent OS，跟企业现有体系直接咬合，不需要跨语言胶水。', 'OryxOS is Java-native, meshing directly with your existing stack — no cross-language glue required.') }}</p>
          </div>
          <div class="oryxos-problem-compare">
            <div class="oryxos-compare-item oryxos-compare-bad">
              <div class="oryxos-compare-label">{{ t('今天的做法', 'Today') }}</div>
              <div class="oryxos-compare-rows">
                <div class="oryxos-compare-row">
                  <span class="oryxos-compare-icon">✗</span>
                  <span>{{ t('SaaS Agent 平台，数据出企业', 'SaaS agent platforms, data leaves the enterprise') }}</span>
                </div>
                <div class="oryxos-compare-row">
                  <span class="oryxos-compare-icon">✗</span>
                  <span>{{ t('Node.js / Python Agent 底座，跟 Java 体系割裂', 'Node.js / Python agent runtimes, disconnected from Java stack') }}</span>
                </div>
                <div class="oryxos-compare-row">
                  <span class="oryxos-compare-icon">✗</span>
                  <span>{{ t('安全审查难过，无法纳入现有审计流程', 'Hard to pass security review, cannot join existing audit flow') }}</span>
                </div>
                <div class="oryxos-compare-row">
                  <span class="oryxos-compare-icon">✗</span>
                  <span>{{ t('每个团队重复对接渠道、模型、工具', 'Every team re-integrates channels, models and tools') }}</span>
                </div>
              </div>
            </div>
            <div class="oryxos-compare-item oryxos-compare-good">
              <div class="oryxos-compare-label">OryxOS</div>
              <div class="oryxos-compare-rows">
                <div class="oryxos-compare-row">
                  <span class="oryxos-compare-icon oryxos-icon-ok">✓</span>
                  <span>{{ t('私有部署，数据完全留在企业基础设施', 'Private deployment, data stays in your infrastructure') }}</span>
                </div>
                <div class="oryxos-compare-row">
                  <span class="oryxos-compare-icon oryxos-icon-ok">✓</span>
                  <span>{{ t('Java / Spring Boot 原生，无缝接入现有运维', 'Java / Spring Boot native, fits existing operations') }}</span>
                </div>
                <div class="oryxos-compare-row">
                  <span class="oryxos-compare-icon oryxos-icon-ok">✓</span>
                  <span>{{ t('审计表 day one 落库，可审计地基从一开始就立起来', 'Audit tables persisted from day one') }}</span>
                </div>
                <div class="oryxos-compare-row">
                  <span class="oryxos-compare-icon oryxos-icon-ok">✓</span>
                  <span>{{ t('统一底座，多 Agent 共享渠道、模型、记忆、工具', 'Unified foundation for multi-agent channel/model/memory/tool sharing') }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── ARCHITECTURE ── -->
    <section class="oryxos-section oryxos-flow-section">
      <div class="oryxos-section-inner">
        <div class="oryxos-section-header">
          <div class="oryxos-section-tag">{{ t('架构', 'Architecture') }}</div>
          <h2 class="oryxos-section-title">{{ t('分层架构，九大模块', 'Layered Architecture, Nine Modules') }}</h2>
          <p class="oryxos-section-desc">{{ t('接入层（CLI / Web）→ 引擎层（ReAct 循环）→ 能力层（Provider / Memory / Tool）→ 基础层（Profile / SQLite）', 'Access layer (CLI/Web) → Engine layer (ReAct loop) → Capability layer (Provider/Memory/Tool) → Foundation layer (Profile/SQLite)') }}</p>
        </div>
        <div class="oryxos-arch-wrapper">
          <img src="/architecture.svg" alt="OryxOS architecture" class="oryxos-flow-img" />
        </div>
      </div>
    </section>

    <!-- ── CAPABILITIES ── -->
    <section class="oryxos-section oryxos-primitives-section">
      <div class="oryxos-section-inner oryxos-primitives-inner">
        <div class="oryxos-section-header">
          <div class="oryxos-section-tag">{{ t('核心能力', 'Core Capabilities') }}</div>
          <h2 class="oryxos-section-title">{{ t('五大核心能力', 'Five Core Capabilities') }}</h2>
        </div>
        <div class="oryxos-primitives">
          <div v-for="p in capabilities" :key="p.title" class="oryxos-primitive">
            <div class="oryxos-primitive-header">
              <span class="oryxos-primitive-icon">{{ p.icon }}</span>
              <div>
                <h3 class="oryxos-primitive-title">{{ p.title }}</h3>
                <p class="oryxos-primitive-subtitle">{{ p.subtitle }}</p>
              </div>
            </div>
            <pre class="oryxos-code"><code>{{ p.code }}</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── SCENARIOS ── -->
    <section class="oryxos-section oryxos-scenarios-section">
      <div class="oryxos-section-inner">
        <div class="oryxos-section-header">
          <div class="oryxos-section-tag">{{ t('真实场景', 'Real Scenarios') }}</div>
          <h2 class="oryxos-section-title">{{ t('六个典型落地场景', 'Six Typical Use Cases') }}</h2>
        </div>
        <div class="oryxos-scenarios">
          <div v-for="s in scenarios" :key="s.num" class="oryxos-scenario">
            <div class="oryxos-scenario-num">{{ s.num }}</div>
            <div>
              <h3 class="oryxos-scenario-title">{{ s.title }}</h3>
              <p class="oryxos-scenario-desc">{{ s.desc }}</p>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── INTEGRATION ── -->
    <section class="oryxos-section oryxos-sdk-section">
      <div class="oryxos-section-inner">
        <div class="oryxos-section-header">
          <div class="oryxos-section-tag">{{ t('接入方式', 'Integration') }}</div>
          <h2 class="oryxos-section-title">{{ t('三种接入方式，按需选择', 'Three Ways to Integrate') }}</h2>
        </div>
        <div class="oryxos-sdk-cards">
          <div v-for="i in integrations" :key="i.title" class="oryxos-sdk-card">
            <div class="oryxos-sdk-card-icon">{{ i.icon }}</div>
            <h3 class="oryxos-sdk-card-title">{{ i.title }}</h3>
            <p class="oryxos-sdk-card-desc">{{ i.desc }}</p>
            <pre class="oryxos-code oryxos-sdk-code"><code>{{ i.code }}</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── COMMANDS ── -->
    <section class="oryxos-section oryxos-cmd-section">
      <div class="oryxos-section-inner">
        <div class="oryxos-section-header">
          <div class="oryxos-section-tag">{{ t('命令行', 'CLI') }}</div>
          <h2 class="oryxos-section-title">{{ t('12 个命令覆盖日常操作', '12 Commands for Daily Operations') }}</h2>
          <p class="oryxos-section-desc">{{ t('三种运行模式（chat / serve / gateway）共享同一份 Profile 配置和 Session 存储。', 'Three run modes (chat / serve / gateway) share the same Profile config and Session storage.') }}</p>
        </div>
        <div class="oryxos-cmd-grid">
          <div v-for="c in commands" :key="c.cmd" class="oryxos-cmd-row">
            <code class="oryxos-cmd-subject">{{ c.cmd }}</code>
            <span class="oryxos-cmd-desc">{{ c.desc }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- ── CTA ── -->
    <section class="oryxos-section oryxos-cta-section">
      <div class="oryxos-section-inner">
        <div class="oryxos-cta">
          <h2 class="oryxos-cta-title">{{ t('开始使用 OryxOS', 'Start Building with OryxOS') }}</h2>
          <p class="oryxos-cta-desc">{{ t('初始化工作区，配置 Provider，几分钟内跑起你的第一个 Agent。', 'Initialize a workspace, configure a provider, and run your first agent in minutes.') }}</p>
          <pre class="oryxos-code oryxos-cta-code"><code># 初始化工作区
oryxos init

# 编辑默认 Profile，填入你的 LLM API Key
vim .oryxos/profiles/default.yaml

# 启动交互式对话
oryxos chat

# 或者：以服务模式启动
oryxos serve --port 8080</code></pre>
          <div class="oryxos-cta-links">
            <a class="oryxos-btn-primary" :href="t('/docs/quick-start', '/en/docs/quick-start')">{{ t('查看文档', 'Read the Docs') }}</a>
            <a class="oryxos-btn-ghost" href="https://github.com/XiaoDHuang/oryxos" target="_blank" rel="noopener">GitHub</a>
          </div>
        </div>
      </div>
    </section>

  </div>
</template>

<style scoped>
.oryxos-page {
  min-height: 100vh;
  background: #0a0a0a;
  color: #ffffff;
  font-family: inherit;
  overflow-x: hidden;
}

/* ── Hero ── */
.oryxos-hero {
  position: relative;
  padding: 120px 24px 100px;
  text-align: center;
  overflow: hidden;
  background: linear-gradient(180deg, #0a0a0a 0%, #171717 100%);
}

.oryxos-hero-bg {
  position: absolute;
  inset: 0;
  overflow: hidden;
}

.oryxos-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(249, 115, 22, 0.05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(249, 115, 22, 0.05) 1px, transparent 1px);
  background-size: 50px 50px;
}

.oryxos-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(100px);
}

.oryxos-orb-1 {
  width: 600px;
  height: 600px;
  background: radial-gradient(circle, rgba(249, 115, 22, 0.15) 0%, transparent 70%);
  top: -300px;
  right: -200px;
}

.oryxos-orb-2 {
  width: 500px;
  height: 500px;
  background: radial-gradient(circle, rgba(234, 88, 12, 0.1) 0%, transparent 70%);
  bottom: -200px;
  left: -150px;
}

.oryxos-hero-inner {
  position: relative;
  max-width: 800px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  z-index: 1;
}

.oryxos-badge {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 10px 24px;
  border-radius: 24px;
  border: 1px solid rgba(249, 115, 22, 0.3);
  background: rgba(249, 115, 22, 0.1);
  color: #fb923c;
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 36px;
}

.oryxos-badge-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: linear-gradient(135deg, #fb923c, #f97316);
  animation: pulse 2s infinite;
  box-shadow: 0 0 20px rgba(249, 115, 22, 0.5);
}

@keyframes pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.5; transform: scale(1.4); }
}

.oryxos-title {
  margin: 0 0 20px;
  line-height: 1;
}

.oryxos-title-name {
  font-size: clamp(80px, 16vw, 150px);
  font-weight: 900;
  letter-spacing: -0.04em;
  background: linear-gradient(135deg, #ffffff 0%, #fb923c 50%, #f97316 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.oryxos-title-sub {
  font-size: 22px;
  color: #a3a3a3;
  margin: 0 0 28px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.oryxos-hero-desc {
  font-size: 18px;
  line-height: 1.8;
  color: #737373;
  max-width: 700px;
  margin: 0 0 44px;
  font-weight: 500;
}

.oryxos-hero-actions {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  justify-content: center;
  margin-bottom: 36px;
}

.oryxos-btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 16px 36px;
  border-radius: 12px;
  background: linear-gradient(135deg, #fb923c 0%, #f97316 100%);
  color: #0a0a0a;
  font-weight: 700;
  font-size: 16px;
  text-decoration: none;
  transition: all 0.3s ease;
  box-shadow: 0 6px 30px rgba(249, 115, 22, 0.4);
}

.oryxos-btn-primary:hover {
  transform: translateY(-3px);
  box-shadow: 0 12px 40px rgba(249, 115, 22, 0.5);
}

.oryxos-btn-arrow {
  transition: transform 0.3s ease;
}

.oryxos-btn-primary:hover .oryxos-btn-arrow {
  transform: translateX(5px);
}

.oryxos-btn-ghost {
  padding: 16px 36px;
  border-radius: 12px;
  border: 2px solid #404040;
  color: #d4d4d4;
  font-weight: 700;
  font-size: 16px;
  text-decoration: none;
  transition: all 0.3s ease;
  background: transparent;
}

.oryxos-btn-ghost:hover {
  border-color: #f97316;
  color: #fb923c;
  background: rgba(249, 115, 22, 0.1);
  transform: translateY(-2px);
}

.oryxos-hero-note {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  justify-content: center;
}

.oryxos-tech-tag {
  padding: 8px 18px;
  border-radius: 24px;
  background: #171717;
  border: 1px solid #404040;
  color: #a3a3a3;
  font-size: 13px;
  font-weight: 600;
  transition: all 0.3s ease;
}

.oryxos-tech-tag:hover {
  border-color: #f97316;
  color: #fb923c;
  background: rgba(249, 115, 22, 0.1);
}

/* ── Section ── */
.oryxos-section {
  padding: 100px 24px;
  position: relative;
}

.oryxos-section-inner {
  max-width: 1100px;
  margin: 0 auto;
}

.oryxos-primitives-inner {
  max-width: 1400px;
}

.oryxos-section-header {
  text-align: center;
  margin-bottom: 64px;
}

.oryxos-section-tag {
  display: inline-block;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.15em;
  text-transform: uppercase;
  color: #0a0a0a;
  padding: 8px 20px;
  border-radius: 24px;
  background: linear-gradient(135deg, #fb923c, #f97316);
  margin-bottom: 20px;
}

.oryxos-section-title {
  font-size: clamp(28px, 4vw, 40px);
  font-weight: 800;
  color: #ffffff;
  margin: 0 0 20px;
  letter-spacing: -0.02em;
}

.oryxos-section-desc {
  font-size: 17px;
  color: #737373;
  max-width: 700px;
  margin: 0 auto;
  line-height: 1.7;
}

/* ── Problem ── */
.oryxos-problem-section {
  background: #0a0a0a;
}

.oryxos-problem {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 64px;
  align-items: start;
}

.oryxos-problem-text p {
  color: #a3a3a3;
  line-height: 1.8;
  margin: 0 0 20px;
  font-size: 16px;
}

.oryxos-problem-item strong {
  color: #ffffff;
  display: block;
  margin-bottom: 8px;
  font-size: 18px;
  font-weight: 700;
}

.oryxos-solution-line {
  color: #fb923c !important;
  font-weight: 600;
  font-size: 17px !important;
  padding: 20px 24px;
  background: rgba(249, 115, 22, 0.1);
  border-left: 4px solid #f97316;
  border-radius: 0 16px 16px 0;
}

.oryxos-problem-compare {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.oryxos-compare-item {
  padding: 32px;
  border-radius: 20px;
  border: 2px solid #262626;
  transition: all 0.3s ease;
  background: #171717;
}

.oryxos-compare-bad:hover {
  border-color: #404040;
}

.oryxos-compare-good {
  background: rgba(249, 115, 22, 0.08);
  border-color: rgba(249, 115, 22, 0.3);
}

.oryxos-compare-good:hover {
  border-color: #f97316;
  box-shadow: 0 16px 50px rgba(249, 115, 22, 0.15);
  transform: translateY(-4px);
}

.oryxos-compare-label {
  font-size: 13px;
  font-weight: 700;
  color: #737373;
  margin-bottom: 20px;
  text-transform: uppercase;
  letter-spacing: 0.1em;
}

.oryxos-compare-rows {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.oryxos-compare-row {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  font-size: 15px;
  color: #a3a3a3;
  line-height: 1.6;
}

.oryxos-compare-icon {
  flex-shrink: 0;
  font-style: normal;
  color: #525252;
  font-weight: 700;
  width: 20px;
  font-size: 18px;
}

.oryxos-icon-ok {
  color: #f97316;
}

/* ── Architecture ── */
.oryxos-flow-section {
  background: linear-gradient(180deg, #171717 0%, #0a0a0a 100%);
}

.oryxos-arch-wrapper {
  padding: 32px;
  background: #171717;
  border-radius: 24px;
  border: 2px solid #262626;
  transition: all 0.3s ease;
}

.oryxos-arch-wrapper:hover {
  border-color: rgba(249, 115, 22, 0.3);
  box-shadow: 0 20px 60px rgba(249, 115, 22, 0.1);
}

.oryxos-flow-img {
  width: 100%;
  display: block;
  border-radius: 16px;
}

/* ── Capabilities ── */
.oryxos-primitives-section {
  background: #0a0a0a;
}

.oryxos-primitives {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  grid-auto-rows: 1fr;
  gap: 24px;
}

.oryxos-primitive {
  padding: 32px;
  border-radius: 24px;
  border: 2px solid #262626;
  background: #171717;
  display: flex;
  flex-direction: column;
  gap: 20px;
  transition: all 0.3s ease;
  min-width: 0;
  overflow: hidden;
  position: relative;
}

.oryxos-primitive::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 4px;
  background: linear-gradient(90deg, #fb923c, #f97316);
  opacity: 0;
  transition: opacity 0.3s ease;
}

.oryxos-primitive:hover {
  border-color: rgba(249, 115, 22, 0.4);
  box-shadow: 0 20px 60px rgba(249, 115, 22, 0.15);
  transform: translateY(-6px);
}

.oryxos-primitive:hover::before {
  opacity: 1;
}

.oryxos-primitive-header {
  display: flex;
  align-items: flex-start;
  gap: 18px;
}

.oryxos-primitive-icon {
  font-size: 36px;
  flex-shrink: 0;
  width: 64px;
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #fb923c 0%, #f97316 100%);
  border-radius: 18px;
  box-shadow: 0 8px 20px rgba(249, 115, 22, 0.3);
}

.oryxos-primitive-title {
  font-size: 20px;
  font-weight: 700;
  color: #ffffff;
  margin: 0 0 6px;
}

.oryxos-primitive-subtitle {
  font-size: 14px;
  color: #737373;
  margin: 0;
  font-weight: 500;
}

.oryxos-code {
  background: #0a0a0a;
  border: 1px solid #262626;
  border-radius: 16px;
  padding: 20px 24px;
  font-size: 13px;
  line-height: 1.7;
  color: #a3a3a3;
  overflow-x: auto;
  margin: 0;
  white-space: pre;
  flex: 1;
  transition: all 0.3s ease;
}

.oryxos-primitive:hover .oryxos-code {
  border-color: rgba(249, 115, 22, 0.3);
  color: #d4d4d4;
}

.oryxos-code code {
  font-family: 'JetBrains Mono', 'Fira Code', 'Cascadia Code', monospace;
  background: none;
  color: inherit;
}

/* ── Scenarios ── */
.oryxos-scenarios-section {
  background: #171717;
}

.oryxos-scenarios {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 28px;
}

.oryxos-scenario {
  display: flex;
  gap: 24px;
  padding: 32px;
  border-radius: 20px;
  border: 2px solid #262626;
  background: #0a0a0a;
  transition: all 0.3s ease;
  position: relative;
  overflow: hidden;
}

.oryxos-scenario::after {
  content: '';
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  height: 3px;
  background: linear-gradient(90deg, #fb923c, #f97316);
  transform: scaleX(0);
  transform-origin: left;
  transition: transform 0.3s ease;
}

.oryxos-scenario:hover {
  border-color: rgba(249, 115, 22, 0.3);
  transform: translateX(6px);
  box-shadow: 0 12px 40px rgba(249, 115, 22, 0.1);
}

.oryxos-scenario:hover::after {
  transform: scaleX(1);
}

.oryxos-scenario-num {
  font-size: 40px;
  font-weight: 900;
  background: linear-gradient(135deg, #fb923c 0%, #f97316 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  line-height: 1;
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
  opacity: 0.4;
  transition: opacity 0.3s ease;
}

.oryxos-scenario:hover .oryxos-scenario-num {
  opacity: 1;
}

.oryxos-scenario-title {
  font-size: 18px;
  font-weight: 700;
  color: #ffffff;
  margin: 0 0 10px;
}

.oryxos-scenario-desc {
  font-size: 15px;
  color: #737373;
  line-height: 1.7;
  margin: 0;
}

/* ── Integration ── */
.oryxos-sdk-section {
  background: linear-gradient(135deg, #7c2d12 0%, #9a3412 50%, #c2410c 100%);
  position: relative;
  overflow: hidden;
}

.oryxos-sdk-section::before {
  content: '';
  position: absolute;
  inset: 0;
  background: url("data:image/svg+xml,%3Csvg width='60' height='60' viewBox='0 0 60 60' xmlns='http://www.w3.org/2000/svg'%3E%3Cg fill='none' fill-rule='evenodd'%3E%3Cg fill='%23ffffff' fill-opacity='0.03'%3E%3Cpath d='M36 34v-4h-2v4h-4v2h4v4h2v-4h4v-2h-4zm0-30V0h-2v4h-4v2h4v4h2V6h4V4h-4zM6 34v-4H4v4H0v2h4v4h2v-4h4v-2H6zM6 4V0H4v4H0v2h4v4h2V6h4V4H6z'/%3E%3C/g%3E%3C/g%3E%3C/svg%3E");
}

.oryxos-sdk-section .oryxos-section-title {
  color: #ffffff;
}

.oryxos-sdk-section .oryxos-section-desc {
  color: rgba(255, 255, 255, 0.7);
}

.oryxos-sdk-cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 24px;
  position: relative;
  z-index: 1;
  max-width: 100%;
}

.oryxos-sdk-card {
  background: rgba(0, 0, 0, 0.3);
  border: 2px solid rgba(255, 255, 255, 0.1);
  border-radius: 24px;
  padding: 32px 28px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  transition: all 0.3s ease;
  backdrop-filter: blur(10px);
  min-width: 0;
  overflow: hidden;
}

.oryxos-sdk-card:hover {
  background: rgba(0, 0, 0, 0.5);
  border-color: rgba(255, 255, 255, 0.2);
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
  transform: translateY(-8px);
}

.oryxos-sdk-card-icon {
  font-size: 40px;
  width: 72px;
  height: 72px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 20px;
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.oryxos-sdk-card-title {
  font-size: 20px;
  font-weight: 700;
  color: #ffffff;
  margin: 0;
}

.oryxos-sdk-card-desc {
  font-size: 15px;
  color: rgba(255, 255, 255, 0.7);
  line-height: 1.7;
  margin: 0;
  flex: 1;
}

.oryxos-sdk-code {
  font-size: 12px;
  background: rgba(0, 0, 0, 0.5) !important;
  border: 1px solid rgba(255, 255, 255, 0.1) !important;
  color: #d4d4d4 !important;
  max-width: 100%;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

/* ── Commands ── */
.oryxos-cmd-section {
  background: #0a0a0a;
}

.oryxos-cmd-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

.oryxos-cmd-row {
  display: flex;
  align-items: baseline;
  gap: 16px;
  padding: 18px 24px;
  border-radius: 16px;
  background: #171717;
  border: 2px solid #262626;
  transition: all 0.3s ease;
}

.oryxos-cmd-row:hover {
  border-color: #f97316;
  transform: translateX(6px);
  box-shadow: 0 8px 30px rgba(249, 115, 22, 0.1);
}

.oryxos-cmd-subject {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 13px;
  color: #0a0a0a;
  background: linear-gradient(135deg, #fb923c, #f97316);
  border: none;
  padding: 6px 14px;
  border-radius: 10px;
  flex-shrink: 0;
  white-space: nowrap;
  font-weight: 700;
}

.oryxos-cmd-desc {
  font-size: 15px;
  color: #a3a3a3;
  font-weight: 500;
}

/* ── CTA ── */
.oryxos-cta-section {
  background: linear-gradient(135deg, #0a0a0a 0%, #171717 50%, #0a0a0a 100%);
  position: relative;
  overflow: hidden;
}

.oryxos-cta-section::before {
  content: '';
  position: absolute;
  inset: 0;
  background: url("data:image/svg+xml,%3Csvg width='60' height='60' viewBox='0 0 60 60' xmlns='http://www.w3.org/2000/svg'%3E%3Cg fill='none' fill-rule='evenodd'%3E%3Cg fill='%23f97316' fill-opacity='0.05'%3E%3Cpath d='M36 34v-4h-2v4h-4v2h4v4h2v-4h4v-2h-4zm0-30V0h-2v4h-4v2h4v4h2V6h4V4h-4zM6 34v-4H4v4H0v2h4v4h2v-4h4v-2H6zM6 4V0H4v4H0v2h4v4h2V6h4V4H6z'/%3E%3C/g%3E%3C/g%3E%3C/svg%3E");
}

.oryxos-cta {
  text-align: center;
  max-width: 760px;
  margin: 0 auto;
  position: relative;
  z-index: 1;
}

.oryxos-cta-title {
  font-size: 36px;
  font-weight: 800;
  color: #ffffff;
  margin: 0 0 20px;
  letter-spacing: -0.02em;
}

.oryxos-cta-desc {
  font-size: 18px;
  color: #737373;
  margin: 0 0 40px;
  line-height: 1.7;
}

.oryxos-cta-code {
  text-align: left;
  margin-bottom: 40px;
  background: rgba(0, 0, 0, 0.5) !important;
  border: 2px solid #262626 !important;
  color: #d4d4d4 !important;
  font-size: 14px;
  border-radius: 16px;
}

.oryxos-cta-links {
  display: flex;
  gap: 20px;
  justify-content: center;
  flex-wrap: wrap;
}

.oryxos-cta-section .oryxos-btn-primary {
  background: linear-gradient(135deg, #fb923c, #f97316);
  color: #0a0a0a;
  padding: 18px 40px;
  font-size: 17px;
}

.oryxos-cta-section .oryxos-btn-primary:hover {
  transform: translateY(-3px);
  box-shadow: 0 12px 40px rgba(249, 115, 22, 0.4);
}

.oryxos-cta-section .oryxos-btn-ghost {
  border-color: #404040;
  color: #d4d4d4;
  background: transparent;
  padding: 18px 40px;
  font-size: 17px;
}

.oryxos-cta-section .oryxos-btn-ghost:hover {
  border-color: #f97316;
  color: #fb923c;
  background: rgba(249, 115, 22, 0.1);
}

/* ── Responsive ── */
@media (max-width: 900px) {
  .oryxos-sdk-cards { grid-template-columns: 1fr; }
}

@media (max-width: 768px) {
  .oryxos-hero { padding: 80px 20px 60px; }
  .oryxos-problem { grid-template-columns: 1fr; gap: 48px; }
  .oryxos-primitives { grid-template-columns: 1fr; }
  .oryxos-scenarios { grid-template-columns: 1fr; }
  .oryxos-cmd-grid { grid-template-columns: 1fr; }
  .oryxos-section { padding: 60px 20px; }
}
</style>
