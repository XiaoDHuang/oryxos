# Contract: Memory 端口、文件与 Tool

## 1. Core Port

位置：`oryxos-core`。这是引擎与能力实现之间唯一的 Memory 公共边界。

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

### buildContext

- `session` 必填；`maxHistoryTurns >= 0`。
- 每次调用重新读取长期记忆。
- 非空长期记忆渲染为一条 `SystemMessage`，位于会话历史之前。
- 历史仅保留最近 `maxHistoryTurns` 条；角色、内容、Tool Call 与顺序不改。
- 长期记忆为空时不产生空 System Message。

### remember

- `content` 非 null/非空白；不得包含独占整行的核心或归档 header。
- `scope == null` 等同 `ARCHIVAL`。
- 成功返回前必须完成持久写；下一次 load/recall 立即可见。

### recall

- `keyword` 非 null/非空白。
- 返回只来自完整归档区的匹配行，顺序与物理文件一致。
- 无匹配返回空列表，不抛“未找到”异常。

## 2. LongTermMemory

位置：`oryxos-memory`，操作显式传入 workspace 下的 `memory/MEMORY.md`。

```java
public final class LongTermMemory {
    public void append(String content, MemoryScope scope);
    public String load();
    public List<String> recallByKeyword(String keyword);
    public String truncateIfNeeded(String archive);
}
```

行为：

- 文件格式、兼容迁移和状态转换以 [data-model.md](../data-model.md) 为准。
- `truncateIfNeeded` 只接受归档正文；`length <= 4000` 原样返回，超过时返回最后 4000 个 Java `char`。
- `load` 不物理截断文件；核心区完整，归档仅截注入视图。
- 每个方法现读文件，不保留内容缓存。
- 解析/IO 错误为带 cause 的 `IllegalStateException`，中文消息不得包含绝对路径。
- 输入错误为 `IllegalArgumentException`。

## 3. Memory Tool Contract

### save_memory

| 项 | 契约 |
|---|---|
| 名称 | `save_memory` |
| content | 必填，长期保存的内容 |
| scope | 可选；`core` / `archival`，空缺省 `archival` |
| 成功结果 | `已记住` |
| 非法 scope | 明确失败；不得调用 MemoryService |

scope 解析先trim，再仅折叠ASCII A–Z并与core/archival常量精确比较；只接受两个固定值且不调用Unicode case transformation API。该 Tool 不自动判断内容重要性，也不在后台触发。

### recall_memory

| 项 | 契约 |
|---|---|
| 名称 | `recall_memory` |
| keyword | 必填，大小写敏感包含匹配 |
| 有命中 | 按顺序以换行拼接匹配行 |
| 无命中 | `没有找到相关记忆` |

## 4. Tool Registration & Audit

- 两个 Tool 是 OryxOS 内置能力，必须在 `ToolRegistry.freeze()` 前显式加入 trusted builtins。
- 普通 Java Plugin 的默认拒绝 guard 不得放宽。
- Profile 未声明时不得出现在 Prompt 工具表；声明后才能被模型调用。
- 执行链固定为 `ReActLoop → ToolExecutor → AnnotatedToolAdapter → MemoryTools → MemoryService`。
- 成功、失败和重试的最终逻辑结果继续由 `ToolExecutor` 写入一条 `tool_invocations`，Memory 不新增旁路审计。

## 5. Prompt Ordering

最终消息顺序：

1. 身份 + Bootstrap/Skill + 当前日期时间 System Message；
2. 非空长期记忆 System Message；
3. 最近会话历史；
4. 工具声明仍通过 Prompt 的工具表字段传递，不渲染进会话 Message。

空长期记忆退化为第17/20节既有顺序，不产生空消息或第二条构建路径。

## 6. Initialization Contract

`oryxos init` 生成：

```markdown
# Long-term memory

## 核心记忆

## 归档记忆
```

第二次 init 遇到已有工作区仍整体跳过，不覆盖任何用户内容。运行时只在 workspace 已存在时补齐缺失的 memory 目录/文件。

## 7. Out of Scope

- 无 `memory_entries` 表或 schema 变更；
- 无 `memory.backend` / 路径配置键或 Profile 新字段；
- 无 SQLite 长期记忆、Mem0、向量/语义检索、自动提炼、知识图谱、Memory Wiki、压缩或缓存；
- 无多租户、每用户/Profile 文件隔离或多进程共享写保证。
