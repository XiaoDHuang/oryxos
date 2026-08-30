package com.oryxos.memory;

import com.oryxos.core.memory.MemoryScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工作区级文件式长期记忆.
 *
 * @author OryxOS Contributors
 */
public final class LongTermMemory {

  private static final Logger LOGGER = LoggerFactory.getLogger(LongTermMemory.class);

  private static final String TITLE = "# Long-term memory";
  private static final String CORE_HEADER = "## 核心记忆";
  private static final String ARCHIVE_HEADER = "## 归档记忆";
  private static final int MAX_ARCHIVE_CHARS = 4000;
  private static final ConcurrentMap<Path, ReentrantLock> PATH_LOCKS = new ConcurrentHashMap<>();

  private final Path workspace;
  private final Path memoryDirectory;
  private final Path memoryFile;
  private final ReentrantLock lock;

  /** 绑定到显式工作区,避免测试或嵌入方修改进程工作目录. */
  public LongTermMemory(Path workspace) {
    this.workspace =
        Objects.requireNonNull(workspace, "Memory工作区不能为空").toAbsolutePath().normalize();
    memoryDirectory = this.workspace.resolve("memory").normalize();
    memoryFile = memoryDirectory.resolve("MEMORY.md").normalize();
    lock = PATH_LOCKS.computeIfAbsent(memoryFile, ignored -> new ReentrantLock());
  }

  /** 把内容追加到指定分区. */
  public void append(String content, MemoryScope scope) {
    validateContent(content);
    MemoryScope selected = scope == null ? MemoryScope.ARCHIVAL : scope;
    withLock(
        () -> {
          Document current = readDocument();
          String entry = "- [" + LocalDate.now() + "] " + content;
          Document updated =
              selected == MemoryScope.CORE
                  ? new Document(appendLine(current.core(), entry), current.archive())
                  : new Document(current.core(), appendLine(current.archive(), entry));
          writeDocument(updated);
          return null;
        });
  }

  /** 加载适合Prompt注入的长期记忆视图. */
  public String load() {
    return withLock(
        () -> {
          Document document = readDocument();
          String archive = truncateIfNeeded(document.archive());
          if (document.core().isBlank() && archive.isBlank()) {
            return "";
          }
          StringBuilder context = new StringBuilder();
          if (!document.core().isBlank()) {
            context.append(CORE_HEADER).append('\n').append(document.core());
          }
          if (!archive.isBlank()) {
            if (!context.isEmpty()) {
              context.append("\n\n");
            }
            context.append(ARCHIVE_HEADER).append('\n').append(archive);
          }
          return context.toString();
        });
  }

  /** 按关键词检索完整归档区. */
  public List<String> recallByKeyword(String keyword) {
    if (keyword == null || keyword.isBlank()) {
      throw new IllegalArgumentException("记忆检索关键词不能为空");
    }
    return withLock(
        () -> readDocument().archive().lines().filter(line -> line.contains(keyword)).toList());
  }

  /** 仅保留归档区最近四千字符. */
  public String truncateIfNeeded(String archive) {
    if (archive == null) {
      throw new IllegalArgumentException("归档记忆不能为空");
    }
    if (archive.length() <= MAX_ARCHIVE_CHARS) {
      return archive;
    }
    return archive.substring(archive.length() - MAX_ARCHIVE_CHARS);
  }

  private Document readDocument() {
    requireWorkspace();
    try {
      Files.createDirectories(memoryDirectory);
      if (!Files.exists(memoryFile)) {
        Document empty = new Document("", "");
        writeDocument(empty);
        return empty;
      }
      if (!Files.isRegularFile(memoryFile)) {
        throw new IllegalStateException("长期记忆文件不是普通文件");
      }
      ParsedDocument parsed = parse(Files.readString(memoryFile, StandardCharsets.UTF_8));
      if (parsed.repairNeeded()) {
        writeDocument(parsed.document());
      }
      return parsed.document();
    } catch (IOException exception) {
      throw new IllegalStateException("长期记忆文件读取失败", exception);
    }
  }

  private ParsedDocument parse(String raw) {
    String normalized = normalizeLineEndings(raw);
    List<String> lines = new ArrayList<>(Arrays.asList(normalized.split("\n", -1)));
    List<Integer> coreHeaders = indexesOf(lines, CORE_HEADER);
    List<Integer> archiveHeaders = indexesOf(lines, ARCHIVE_HEADER);
    if (coreHeaders.size() > 1 || archiveHeaders.size() > 1) {
      throw new IllegalStateException("长期记忆分区重复");
    }
    if (coreHeaders.isEmpty() && archiveHeaders.isEmpty()) {
      String legacy = contentWithoutTitle(lines);
      return new ParsedDocument(new Document("", legacy), true);
    }
    if (coreHeaders.isEmpty()) {
      int archiveIndex = archiveHeaders.getFirst();
      validatePrefix(lines, archiveIndex);
      return new ParsedDocument(
          new Document("", section(lines, archiveIndex + 1, lines.size())), true);
    }
    if (archiveHeaders.isEmpty()) {
      int coreIndex = coreHeaders.getFirst();
      validatePrefix(lines, coreIndex);
      return new ParsedDocument(
          new Document(section(lines, coreIndex + 1, lines.size()), ""), true);
    }
    int coreIndex = coreHeaders.getFirst();
    int archiveIndex = archiveHeaders.getFirst();
    if (coreIndex > archiveIndex) {
      throw new IllegalStateException("长期记忆分区顺序错误");
    }
    validatePrefix(lines, coreIndex);
    return new ParsedDocument(
        new Document(
            section(lines, coreIndex + 1, archiveIndex),
            section(lines, archiveIndex + 1, lines.size())),
        false);
  }

  private void writeDocument(Document document) {
    requireWorkspace();
    Path temporary = null;
    try {
      Files.createDirectories(memoryDirectory);
      temporary = Files.createTempFile(memoryDirectory, "memory-", ".tmp");
      Files.writeString(
          temporary,
          document.render(),
          StandardCharsets.UTF_8,
          StandardOpenOption.TRUNCATE_EXISTING);
      try {
        Files.move(
            temporary,
            memoryFile,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException | AccessDeniedException exception) {
        // Windows可能拒绝覆盖式原子移动,普通替换仍保持临时文件不会半写正式文件。
        Files.move(temporary, memoryFile, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("长期记忆文件写入失败", exception);
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException exception) {
          // 正式文件结果已确定,清理失败只告警且不泄漏本地路径。
          LOGGER.warn("长期记忆临时文件清理失败，异常类别={}", exception.getClass().getSimpleName());
        }
      }
    }
  }

  private void requireWorkspace() {
    if (!Files.isDirectory(workspace)) {
      throw new IllegalStateException("未找到Memory工作区,请先运行oryxos init");
    }
  }

  private <T> T withLock(Supplier<T> action) {
    lock.lock();
    try {
      return action.get();
    } finally {
      lock.unlock();
    }
  }

  private static void validateContent(String content) {
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("记忆内容不能为空");
    }
    boolean forgedHeader =
        normalizeLineEndings(content)
            .lines()
            .anyMatch(line -> CORE_HEADER.equals(line) || ARCHIVE_HEADER.equals(line));
    if (forgedHeader) {
      throw new IllegalArgumentException("记忆内容不能伪造分区标题");
    }
  }

  private static List<Integer> indexesOf(List<String> lines, String expected) {
    List<Integer> indexes = new ArrayList<>();
    for (int index = 0; index < lines.size(); index++) {
      if (expected.equals(lines.get(index))) {
        indexes.add(index);
      }
    }
    return indexes;
  }

  private static void validatePrefix(List<String> lines, int headerIndex) {
    boolean titleSeen = false;
    for (int index = 0; index < headerIndex; index++) {
      String line = lines.get(index);
      if (line.isBlank()) {
        continue;
      }
      if (TITLE.equals(line) && !titleSeen) {
        titleSeen = true;
        continue;
      }
      throw new IllegalStateException("长期记忆分区前存在无法识别的内容");
    }
  }

  private static String contentWithoutTitle(List<String> lines) {
    List<String> content = new ArrayList<>(lines);
    int firstContent = 0;
    while (firstContent < content.size() && content.get(firstContent).isBlank()) {
      firstContent++;
    }
    if (firstContent < content.size() && TITLE.equals(content.get(firstContent))) {
      content.remove(firstContent);
    }
    return section(content, 0, content.size());
  }

  private static String section(List<String> lines, int start, int end) {
    int first = start;
    int last = end;
    while (first < last && lines.get(first).isBlank()) {
      first++;
    }
    while (last > first && lines.get(last - 1).isBlank()) {
      last--;
    }
    return String.join("\n", lines.subList(first, last));
  }

  private static String appendLine(String existing, String entry) {
    return existing.isEmpty() ? entry : existing + '\n' + entry;
  }

  private static String normalizeLineEndings(String value) {
    return value.replace("\r\n", "\n").replace('\r', '\n');
  }

  private record ParsedDocument(Document document, boolean repairNeeded) {}

  private record Document(String core, String archive) {

    private String render() {
      StringBuilder output =
          new StringBuilder(TITLE).append("\n\n").append(CORE_HEADER).append('\n');
      if (!core.isEmpty()) {
        output.append('\n').append(core).append('\n');
      }
      output.append('\n').append(ARCHIVE_HEADER).append('\n');
      if (!archive.isEmpty()) {
        output.append('\n').append(archive).append('\n');
      }
      return output.toString();
    }
  }
}
