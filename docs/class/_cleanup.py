# -*- coding: utf-8 -*-
"""Post-process generated markdown: fix 2-column test-class tables,
complex tables (19节), and '交付物' list concatenations."""
import glob
import os
import re

DOC_DIR = os.path.dirname(os.path.abspath(__file__))


def replace_between(text, start, end, mid):
    i = text.index(start) + len(start)
    j = text.index(end, i)
    return text[:i] + "\n\n" + mid + "\n\n" + text[j:]


def fix_table(text, intro, rows, end, header=("测试类", "覆盖的验收点")):
    """Replace the mangled 2-col table between `intro` and `end`."""
    i = text.index(intro) + len(intro)
    j = text.index(end, i)
    h = f"| {header[0]} | {header[1]} |\n|---|---|"
    body = "\n".join(f"| `{c}` | {d} |" for c, d in rows)
    return text[:i] + "\n\n" + h + "\n" + body + "\n\n" + text[j:]


def split_deliverables(text):
    """Split '交付物' label list into separate paragraphs (scoped to the
    '本节交付物' block only, to avoid touching body text)."""
    labels = r"(代码|测试|配置|表|约定|说明|扩展交付)："
    marker = "本节交付物（Spec-Kit 拆解锚点）："
    out = []
    pos = 0
    while True:
        i = text.find(marker, pos)
        if i == -1:
            out.append(text[pos:])
            break
        j = text.find("\n## ", i + len(marker))
        if j == -1:
            j = len(text)
        block = text[i:j]
        block = re.sub(r"(?<!\n)" + labels, r"\n\n\1：", block)
        out.append(text[pos:i])
        out.append(block)
        pos = j
    return "".join(out)


def main():
    edits = [
        ("第16节：Agent Provider 原理解析、实现与代码讲解.md",
         "四个单测类，逐条对应验收标准：",
         "最值钱的三个测试方法",
         [
             ("ProfileLoaderTest", "合法 YAML 全字段解析；引用不存在的 provider 报错清晰；坏文件不阻断其余加载；`${ENV}` 占位从环境变量解析"),
             ("ProviderServiceTest", "双 provider 按名路由不串台；未知名抛异常；成功/失败都落审计；自动执行关闭"),
             ("ToolSchemaAdapterTest", "`OryxTool` 的 schema 翻译成 Spring AI 格式后字段一一对齐；只翻译、产物里不含任何执行逻辑"),
             ("LlmCallRepositoryTest", "手工建表脚本建出的 `llm_calls` 能存能读，`success`/`error_message` 两列真实存在"),
         ]),
        ("第17节：ReAct 原理解析、实现与代码讲解.md",
         "五个测试类对应五个交付物：",
         "两个最值钱的回归测试写出来",
         [
             ("ReActLoopTest", "无工具调用一轮收尾；有工具调用则执行并回填进下一轮；转满最大轮数强制停（坑一回归）；每轮响应和工具结果都累积进 Session（坑三回归）"),
             ("PromptBuilderTest", "四部分顺序正确；历史超 N 轮被截断（坑二回归）；system prompt 末尾含当前日期时间"),
             ("ToolExecutorTest", "成功写审计 `success=true`；失败也写 `success=false` 带原因，异常不吞"),
             ("AgentServiceTest", "处理期间 `ProfileContext` 可取到当前 Profile；处理抛异常时 finally 也把它清掉；结束后 Session 被持久化"),
             ("ContextLoaderTest", "改文件后下一次 build 立即读到新内容（无缓存回归）；Skill 引用缺失报错、Bootstrap 缺失 WARN"),
         ]),
        ("第18节：CLI 功能概述、实现思路与代码讲解.md",
         "所以在这就钉死：",
         "关键的一个：",
         [
             ("SessionManagerTest", "同一三元组两次 `getOrCreate` 返回同一个 Session（幂等）；channel/user/profile 任一不同则是不同 Session；id 生成只此一处"),
             ("SessionRepositoryTest", "手工建表脚本建出的 `sessions` 表能存能读；`messages_json` 序列化回读后消息完整；模拟\"重启\"（新建 context 重查）历史还在"),
         ]),
        ("第20节：Tool 体系 原理解析、实现与代码讲解.md",
         "前三块纯单测，第四块 mock 掉 MCP 连接也不碰网：",
         "两个最值钱的：",
         [
             ("OryxToolContractTest", "参数化测试遍历 Registry 里每个工具：name/description/inputSchema 都非空——任何一个工具漏实现 `getInputSchema()`，这里立刻红（\"动手前先检查\"那条的自动化版）"),
             ("ToolRegistryTest", "三种来源的工具都以 `OryxTool` 身份注册进来；按 Profile 的 `tools` 字段过滤后，子集精确匹配、不多不少"),
             ("FileToolsTest / ShellToolsTest / HttpToolsTest", "各自\"正常能跑通 + 越界会被拦\"两条（正文里 `http_get` 那两个用例就是模板）"),
             ("McpToolAdapterTest / McpClientServiceTest", "mock `McpClient`：listTools 返回的工具被包装注册；execute 转发参数原样、结果包成 `ToolResult`；连接失败只 WARN、其余工具照常注册、启动不炸"),
         ]),
    ]

    for fname, intro, end, rows in edits:
        p = os.path.join(DOC_DIR, fname)
        t = open(p, encoding="utf-8").read()
        open(p, "w", encoding="utf-8").write(fix_table(t, intro, rows, end))
        print("fixed table:", fname)

    # ---- 第19节：two test tables ----
    p = os.path.join(DOC_DIR, "第19节：Notify 模块 原理解析、实现与代码讲解.md")
    t = open(p, encoding="utf-8").read()
    mid = ("| 测试点 | 守住的验收项 |\n|---|---|\n"
           "| `notify_channels` 未配置 → 明确报错 | 不是静默失败，Agent 不会以为发出去了 |\n"
           "| `channel` 参数缺省 → 取第一个渠道 | 大多数场景 LLM 只传 content 就够 |\n\n"
           "| 测试点 | 守住的验收项 |\n|---|---|\n"
           "| `enforce` 先于 `send` 被调用 | 白名单不能被\"往外推\"绕过 |")
    t = replace_between(t, "mock `Sandbox` 和 `Adapter` ：", "顺序断言这条最关键", mid)

    # ---- 第19节：渠道总览 table ----
    channel = ("| 场景 | 主流渠道 | 接入形态 | 通用 webhook 档能否覆盖 |\n"
               "|---|---|---|---|\n"
               "| 团队协作群（日报、周报、构建通知） | 企业微信群机器人、飞书/Lark 自定义机器人、钉钉自定义机器人、Slack、Discord、Microsoft Teams | HTTP webhook | ✅ 形态覆盖，payload 格式需适配（见 6.2） |\n"
               "| 个人即时提醒（个人助理类 Agent） | Telegram Bot、Bark（iOS）、Server酱（微信）、ntfy / Gotify（自托管） | HTTP POST/GET | ✅ 基本覆盖 |\n"
               "| 告警值班（生产事故、SLA 告警） | PagerDuty、Opsgenie、阿里云 ARMS / 云监控 | Events API（HTTP，带 routing key 和事件语义） | ⚠️ 能发出去，但 severity / 去重 / 升级策略等字段需专用适配 |\n"
               "| 正式触达（对外通知、审批留痕） | 邮件（SMTP）、短信（阿里云/腾讯云 SMS） | 非 HTTP 或签名认证 API | ❌ 扩展阶段专用 Adapter |")
    t = replace_between(t, "按企业里的典型场景分四类：", "一个判断标准：给一个", channel)

    # ---- 第19节：payload 表 ----
    payload = ("| 渠道 | Webhook URL 形态 | 文本消息 body |\n"
               "|---|---|---|\n"
               "| 企业微信 | `https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=<KEY>` | `{\"msgtype\":\"text\",\"content\":\"...\"}` |\n"
               "| 飞书 / Lark | `https://open.feishu.cn/open-apis/bot/v2/hook/<TOKEN>` | `{\"msg_type\":\"text\",\"text\":\"...\"}` |\n"
               "| 钉钉 | `https://oapi.dingtalk.com/robot/send?access_token=<TOKEN>` | `{\"msgtype\":\"text\",\"content\":\"...\"}` |\n"
               "| Slack | `https://hooks.slack.com/services/T../B../..` | `{\"text\":\"...\"}` |\n"
               "| Discord | `https://discord.com/api/webhooks/<ID>/<TOKEN>` | `{\"content\":\"...\"}` |\n"
               "| Telegram | `https://api.telegram.org/bot<TOKEN>/sendMessage` | `{\"chat_id\":\"...\",\"t...}` |\n"
               "| ntfy | `https://ntfy.sh/<topic>` | 纯文本 body |")
    t = replace_between(t, "各家约定如下：", "几个渠道特有的注意点：", payload)

    open(p, "w", encoding="utf-8").write(t)
    print("fixed tables: 第19节")

    # ---- 交付物 split (all files) ----
    for p in glob.glob(os.path.join(DOC_DIR, "第1*.md")) + glob.glob(os.path.join(DOC_DIR, "第20*.md")):
        t = open(p, encoding="utf-8").read()
        t2 = split_deliverables(t)
        if t2 != t:
            open(p, "w", encoding="utf-8").write(t2)
            print("split 交付物:", os.path.basename(p))


if __name__ == "__main__":
    main()
