# Data Model: 三层 Memory 核心能力

核心阶段不新增关系型 schema。本文件描述受控 Markdown 文档及运行时上下文的逻辑模型；会话记录继续使用既有 `sessions` 表。

## 1. MemoryScope

记忆条目的二值范围，也是 core 端口契约的一部分。

| 值 | 语义 | 注入 | 检索 | 截断 |
|---|---|---|---|---|
| `CORE` | 用户身份、项目背景、关键约束偏好 | 每轮完整注入 | 不参与 recall | 永不截断 |
| `ARCHIVAL` | 长期事实、历史结论和普通偏好 | 仅注入最近 4000 字 | 包含式关键词检索 | 只截注入视图 |

校验规则：服务层 `null` 缺省为 `ARCHIVAL`；Tool 输入空/空白也缺省归档。除 core/archival（忽略大小写和两侧空格）外的字符串全部拒绝。

## 2. LongTermMemoryEntry

文件中的一条长期记忆，不对应数据库实体。

| 字段 | 逻辑类型 | 规则 |
|---|---|---|
| scope | MemoryScope | 由目标分区决定 |
| savedDate | LocalDate | 写入时生成，格式 `yyyy-MM-dd` |
| content | text | 非 null、非空白；不得包含独占整行的两个分区 header |
| physicalOrder | file order | append 后保持，用于 recall 返回顺序和最近归档选择 |

渲染格式：`- [yyyy-MM-dd] <content>`。不做自动提炼、去重、冲突消解、分词或语义嵌入。

## 3. MemoryDocument

工作区级唯一文档，路径 `<workspace>/memory/MEMORY.md`，UTF-8。

```markdown
# Long-term memory

## 核心记忆

## 归档记忆
```

### 结构不变量

1. 核心 header 最多一次、归档 header 最多一次；两者同时存在时核心必须在前。
2. 核心正文在两 header 之间；归档正文在归档 header 之后。
3. `load()` 的 4000 字限制不写回物理文件。
4. `recallByKeyword()` 读取完整、未截断的归档正文。
5. 读写 Memory 不解析或写入同工作区的 `USER.md`。

### 初始化/兼容状态

| 原状态 | 转换 | 数据结果 |
|---|---|---|
| workspace 不存在 | 失败 | 不创建任何替代路径 |
| memory 目录/文件缺失 | 创建标准模板 | 两区为空 |
| 空文件或只有旧标题 | 升级标准模板 | 两区为空 |
| 无分区但有旧业务内容 | 迁入归档区 | 原文逐字保留 |
| 只有核心区 | 尾部补归档区 | 核心正文逐字保留 |
| 只有归档区 | 前置空核心区 | 归档正文逐字保留 |
| 双区正确 | 无结构变更 | 正常读取 |
| header 重复或倒序 | 失败关闭 | 文件逐字不变 |

## 4. MemoryContext

这是逻辑组合而非新增公开 DTO。由 `MemoryService.buildContext` 直接返回消息序列：

| 顺序 | 内容 | 条件 |
|---:|---|---|
| 1 | 长期记忆 System Message | 核心或截断后的归档正文至少一项非空 |
| 2..N | Session 最近消息 | 最多 `maxHistoryTurns` 条，原角色与顺序不变 |

`maxHistoryTurns == 0` 时不返回历史；负数拒绝。身份、Bootstrap/Skill、当前日期时间仍由 PromptBuilder 的首个 System Message 提供，并位于 MemoryContext 之前。

## 5. State Transitions

### 保存

```text
请求内容 + scope
  → 校验输入/header 注入
  → 获取规范化路径锁
  → 读取并解析当前文档
  → 必要时无损升级旧结构
  → 插入目标分区并加日期
  → 同目录临时文件
  → 原子替换正式文件
  → 下一读立即可见
```

任何解析或写入失败都不得产生“成功”结果；正式文件在替换前保持不变。

### 加载

```text
每次重新读文件
  → 核心正文全量
  → 归档正文最后 min(length, 4000) 字
  → 两者都空则无长期记忆消息
```

### 关键词回忆

```text
校验非空关键词
  → 每次重新读文件
  → 只扫描完整归档正文
  → String.contains（大小写敏感）
  → 按物理顺序返回匹配行
```

## 6. Persistence Boundary

- 不新增 `memory_entries` 或其他表。
- 不修改 `sessions`、`tool_invocations`、`llm_calls` schema。
- Tool 调用审计仍写既有 `tool_invocations`；长期记忆正文不复制到新的审计存储。
- JVM 路径锁仅协调 IO，不保存 Memory 内容，不属于业务状态缓存。
