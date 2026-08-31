# Memory：实现与代码讲解

> **2026-08-30 决议更新**：本课按两个 feature 交付。一至五部分保留 **006 文件式基线**的历史讲解及验收范围，已在 `3d60ee0` 归档；其中“核心阶段只用文件 / 不加表 / 不接 Mem0”等限制仅描述 006。用户已批准 **007 三后端续篇**，见第六部分及《技术方案》§5/§9。不能把下文旧限制继续套到 007，也不能把 006 绿灯当成三后端已实现。

## 006 文件式基线（以下为已归档范围）

上一节评审定了方向——接口先行、实现分阶段、核心阶段只做够用且可控的文件式记忆。这节把它变成能跑的代码：用一个稳定的 `MemoryService` 端口把引擎和实现隔开，长期记忆只落 `.oryxos/memory/MEMORY.md`，会话记忆继续复用既有 Session，两个内置 Tool 让 Agent 主动读写长期记忆。

技术栈仍是 JDK 21 + Spring Boot 3.x + Spring AI 的 `@Tool` 注解。核心阶段不新增数据库表、不引入外部记忆服务或向量依赖。

---

## 一、这节要实现什么

一句话：**一个面向引擎的 `MemoryService` 端口，一个文件式 `LongTermMemory` 实现，以及 `save_memory` / `recall_memory` 两个内置 Tool；长期记忆和当前会话历史一起进入 Prompt，但仍保持各自清晰的生命周期。**

这节把第 21 节的三条结论落地：

- **接口墙焊死**——`PromptBuilder` 只依赖核心层的 `MemoryService` 端口，不感知文件路径和解析规则。
- **实现只做当下**——长期记忆只用分区的 `MEMORY.md`，核心区始终在场，归档区按关键词检索并在 4000 字后保留最近内容。
- **写入由 Agent 主动决定**——系统不自动提炼；Agent 通过 `save_memory` 明确选择 `core` 或 `archival`。

模块依赖必须保持单向。`oryxos-memory` 已依赖 `oryxos-core`，所以 `MemoryService` 和作为端口契约一部分的 `MemoryScope` 放在 `oryxos-core`；`MemoryServiceImpl`、`LongTermMemory`、`MemoryTools` 放在 `oryxos-memory`。这与 Provider 能力通过核心端口接入的方式一致，也避免 `oryxos-core` 反向依赖能力实现模块形成 Maven 循环。

---

## 二、动手前先想清楚几件事

**第一，三层记忆不是三套新存储。**

| 层次 | 核心阶段落位 | 本节职责 |
|---|---|---|
| 核心记忆 | `MEMORY.md` 的 `## 核心记忆` 区 | 每次完整注入，不参与截断和检索 |
| 会话记忆 | 既有 Session / SQLite | 继续按最近历史轮数进入 Prompt，不新增表 |
| 归档记忆 | `MEMORY.md` 的 `## 归档记忆` 区 | 按关键词检索，超过 4000 字保留最近内容 |

**第二，`MEMORY.md` 和 `USER.md` 不能混。** `USER.md` 是用户维护的只读 Bootstrap；`MEMORY.md` 是 Agent 通过 Tool 写入的成长记录。任何 Memory 代码都不得写 `USER.md`。

**第三，四条行为契约必须固定。**

- **不缓存。** 每次重新读文件，保证 `save_memory` 后下一轮立即可见。
- **核心区永不截断。** 4000 字阈值只作用于归档区。
- **scope 显式。** `save_memory` 接受 `core` / `archival`，缺省为 `archival`；系统不猜。
- **检索保持简单。** `recall_memory` 只在归档区做包含匹配；核心区本来就完整在场。

![核心记忆区与归档记忆区：截断只作用在归档区，核心区永远完整](../../website/public/images/class-22-2.svg)

---

## 三、代码怎么写

分四步：核心端口 → 文件实现 → Agent Tool → Prompt 集成。

### 第一步：核心端口

`MemoryService` 是 `oryxos-core` 暴露给 `PromptBuilder` 的稳定边界。它统一提供本轮要注入的长期记忆与截断后的会话历史，并提供记住/回忆操作；`MemoryScope` 与方法签名同属端口契约，也放在核心模块。

```java
public interface MemoryService {
    List<Message> buildContext(Session session, int maxHistoryTurns);
    void remember(String content, MemoryScope scope);
    List<String> recall(String keyword);
}

public enum MemoryScope {
    CORE,
    ARCHIVAL
}
```

`buildContext` 返回一个长期记忆 `SystemMessage` 加最近会话历史，使 `PromptBuilder` 不需要知道长期记忆来自哪里；既有身份、Bootstrap、日期时间和工具声明顺序不变。

### 第二步：文件式长期记忆

`LongTermMemory` 位于 `oryxos-memory`，只操作 `.oryxos/memory/MEMORY.md`：

```java
public class LongTermMemory {

    private static final String CORE_HEADER = "## 核心记忆";
    private static final String ARCHIVE_HEADER = "## 归档记忆";
    private static final int MAX_ARCHIVE_CHARS = 4000;

    public void append(String content, MemoryScope scope) {
        String header = scope == MemoryScope.CORE ? CORE_HEADER : ARCHIVE_HEADER;
        writeIntoSection(header, "\n- [" + LocalDate.now() + "] " + content);
    }

    public String load() {
        String raw = Files.readString(memoryFilePath());
        String core = extractSection(raw, CORE_HEADER);
        String archive = truncateIfNeeded(extractSection(raw, ARCHIVE_HEADER));
        return core + "\n" + archive;
    }

    public List<String> recallByKeyword(String keyword) {
        String archive = extractSection(Files.readString(memoryFilePath()), ARCHIVE_HEADER);
        return archive.lines().filter(line -> line.contains(keyword)).toList();
    }

    public String truncateIfNeeded(String archive) {
        if (archive.length() <= MAX_ARCHIVE_CHARS) {
            return archive;
        }
        return archive.substring(archive.length() - MAX_ARCHIVE_CHARS);
    }
}
```

实现必须处理文件尚不存在、区块缺失、空内容和非法 scope；创建文件时一次写入两个标准区块。写入同一文件时要避免并发覆盖，但不做进程内内容缓存。

`MemoryServiceImpl` 很薄：`remember` / `recall` 转发给 `LongTermMemory`；`buildContext` 每次加载长期记忆，再附上当前 Session 最近 `maxHistoryTurns` 条消息。它不创建第二套 Session，也不拼接 `session_id`。

### 第三步：把长期记忆暴露给 Agent

```java
public class MemoryTools {

    private final MemoryService memoryService;

    @Tool(name = "save_memory", description = "记住一件值得长期记住的事")
    public String saveMemory(
            @ToolParam("要记住的内容") String content,
            @ToolParam("core 或 archival，不确定就填 archival") String scope) {
        String selected = scope == null || scope.isBlank() ? "archival" : scope;
        memoryService.remember(content, MemoryScope.valueOf(selected.toUpperCase()));
        return "已记住";
    }

    @Tool(name = "recall_memory", description = "按关键词检索长期记忆")
    public String recallMemory(@ToolParam("检索关键词") String keyword) {
        List<String> hits = memoryService.recall(keyword);
        return hits.isEmpty() ? "没有找到相关记忆" : String.join("\n", hits);
    }
}
```

`MemoryTools` 仍通过第 20 节的注解适配与统一注册表接入。只有 Profile 明确声明 `save_memory` / `recall_memory` 时才向模型暴露，执行与审计继续走既有 `ToolExecutor` 唯一路径。

### 第四步：接入 PromptBuilder

`PromptBuilder` 增加 `MemoryService` 端口依赖。构建顺序保持：身份与 Bootstrap/Skill/当前日期时间 → 长期记忆 → 最近会话历史 → 可用 Tool。为兼容前序调用方，既有构造入口要么保留并注入空记忆实现，要么一次更新全部调用点并由回归测试证明没有第二条组装路径；具体方案在 feature plan 中明确。

**有几样先别做。** SQLite 长期记忆表、Mem0 或其他外部记忆服务、自动抽取、语义/向量检索、Memory Wiki、知识图谱、记忆压缩和缓存全部留到扩展阶段。核心阶段也不新增 `memory.backend` 配置键。

**本节交付物**（Spec-Kit 拆解锚点）：

- 代码：`MemoryService` 接口与 `MemoryScope` 枚举（`oryxos-core`）；`MemoryServiceImpl`、`LongTermMemory`、`MemoryTools`（`oryxos-memory`）
- 测试：`LongTermMemoryTest`、`MemoryToolsTest`、`MemoryServiceImplTest`，以及既有 `PromptBuilderTest` 的 Memory 集成回归
- 文件：`.oryxos/memory/MEMORY.md`，固定包含 `## 核心记忆` / `## 归档记忆` 两个区块
- 集成点：`PromptBuilder` 通过 `MemoryService.buildContext(session, maxHistoryTurns)` 注入长期记忆和截断后的会话历史；`ToolRegistry` 自动接入两个 Memory Tool
- 明确没有：新数据表、新 Profile 字段、新配置键、新外部依赖

---

## 四、验收 harness：把验收标准变成可执行测试

Memory 的文件操作可用 `@TempDir` 隔离，全部 harness 默认作为单元测试执行：

| 测试类 | 覆盖的验收点 |
|---|---|
| `LongTermMemoryTest` | 写后立读；两个 header 初始化与缺失修复；scope 路由；4000 字只截归档、核心一字不丢；关键词只搜归档；空关键词/空内容和非法输入失败方式明确 |
| `MemoryToolsTest` | scope 缺省写归档；显式 core 正确路由；未命中返回“没有找到相关记忆”；非法 scope 不静默改写 |
| `MemoryServiceImplTest` | `buildContext` 每次重新加载长期记忆；长期记忆作为独立 system 消息注入；会话历史按上限保留最近消息且角色不丢 |
| `PromptBuilderTest` | 身份/Bootstrap/日期、长期记忆、会话历史和工具表的顺序与前序契约不漂移；空记忆时不产生无意义内容 |

下面两个守点必须原样落地，测试方法名翻译成英文，课件原文放进 `@DisplayName`：

```java
@Test
void truncationOnlyAffectsArchiveAndPreservesCore() {
    memory.append("用户叫小王，偏好用 Java", MemoryScope.CORE);
    for (int i = 0; i < 500; i++) {
        memory.append("归档流水 " + i, MemoryScope.ARCHIVAL);
    }

    String loaded = memory.load();

    assertTrue(loaded.contains("用户叫小王，偏好用 Java"));
    assertFalse(loaded.contains("归档流水 0"));
    assertTrue(loaded.contains("归档流水 499"));
}

@Test
void writesAreVisibleImmediatelyWithoutCache() {
    memory.append("刚记的事", MemoryScope.ARCHIVAL);
    assertTrue(memory.load().contains("刚记的事"));
    assertFalse(memory.recallByKeyword("刚记的事").isEmpty());
}
```

---

## 五、做完怎么验

harness 全绿后，剩下的人工确认：

- 用真模型说一句值得长期记住的话，确认 Agent 主动调用 `save_memory`；新开会话后 Prompt 中仍带着这条记忆。
- 分别保存核心和归档记忆，目检 `MEMORY.md` 两个区块；制造超过 4000 字的归档内容，确认核心区始终完整。
- 重启 OryxOS 后再次读取，确认长期记忆来自文件而非进程缓存。
- Code review 确认没有写 `USER.md` 的代码路径，也没有 `memory_entries`、Mem0、向量检索或 `memory.backend` 抢跑。
- `save_memory` / `recall_memory` 的实际执行继续产生 `tool_invocations` 审计记录。

到这一步，006 文件链路具备跨会话记住偏好的能力。其后续范围按下面的新决议执行，不再沿用原先把所有后端演进放到扩展阶段的限制。

---

## 六、007 续篇：三后端，保留引擎端口

007 采纳 Markdown / SQLite / 自托管 Mem0 方案，尚处于治理与拆解阶段。保留本课第一步的 `MemoryService` 三个签名及 `MemoryScope`，不改成返回 String 的另一套接口。

**007 交付物（进入 specify/plan 的范围，不是现有代码清单）：**

- memory：`LongTermMemoryStore`、`MarkdownMemoryStore`（复用 006 `LongTermMemory`）、`SqliteMemoryStore`、`Mem0MemoryStore`，以及现有门面/配置的适配。
- storage：`memory_entries` 四字段（id/scope/content/created_at）、scope 索引、实体/仓储/显式迁移。
- 配置：`memory.backend`（markdown 默认 / sqlite / mem0），唯一选择，非法值失败；Mem0 专属键由 plan 核定，无默认云连接。
- 接线：boot 组合层安全接线需在 plan 明确。memory 不得依赖 tool 造成环；缺安全检查不得联网。
- 测试：Store 共同契约（真实适配器）、三后端差异、选择隔离、跨重启、Prompt/Tool 审计回归及 Mem0 锁定版本受控冒烟。测试类名在 tasks 定稿，006 断言保留。

**007 harness 的边界**：共同验证核心完整、scope、保存成功后下一轮可读、故障可观测；分别验证 Markdown 最近 4000 Java char、SQLite 最近 100 条、Mem0 核验后的分页与语义检索。不能假装三者关键词/排序/窗口完全相同；Mem0 默认测试替换 HTTP 传输而不是用内存假 Store 冒充适配器覆盖。

**007 实现前必须核验**：真实自托管协议及版本、核心原文保留、全量分页、工作区身份、写入可见性、超时/重试幂等、出站白名单、服务全部模型/embedding/存储下游及其内部推理审计。课件中的 REST 路径不能直接当成事实。后端推理不能改写核心原文，OryxOS 不增加自动保存触发器、压缩、图谱或自建向量层。

完整故事与 G1–G7 准入清单见 [007 范围拆解](../decisions/007-memory-backends-scope.md)。006 已完成不代表本续篇已完成。

**007 plan 追加批准**：用户已批准 `integrations/mem0-adapter/` 受控 Python 组件及仅 HTTP 白名单提前接入，仍保持 9 个 Maven 模块。外部组件按自有协议使用固定 SDK 暂存推理，PG 原子提交有效状态、版本历史及操作结果；核心不推理、历史不参与常规召回。Java 的 MemoryOutboundGuard、受限错误映射和持久层转换类型，以及外部五表/协议均以 [007 plan](../../specs/007-memory-backends/plan.md) 配套契约为准；先验证机制harness，不能用原版REST或空安全检查替代。设计完成不等于运行验收。
