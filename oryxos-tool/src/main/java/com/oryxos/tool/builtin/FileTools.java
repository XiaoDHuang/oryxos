package com.oryxos.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oryxos.core.tool.ToolResult;
import com.oryxos.tool.sandbox.ActionType;
import com.oryxos.tool.sandbox.Sandbox;
import com.oryxos.tool.sandbox.SandboxAction;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 文件能力在统一安全边界内执行.
 *
 * @author OryxOS Contributors
 */
public final class FileTools {
  private final Sandbox sandbox;
  private final ObjectMapper mapper = new ObjectMapper();

  /** 安全门必须由装配方显式传入，不能在工具内默认放行. */
  public FileTools(Sandbox sandbox) {
    this.sandbox = Objects.requireNonNull(sandbox);
  }

  /** 固定UTF-8读取以保持不同运行环境的文本语义一致. */
  @Tool(name = "read_file", description = "读取UTF-8文件")
  public ToolResult readFile(@ToolParam(description = "文件路径") String path) {
    try {
      Path target = checkedPath(path);
      return ToolResult.ok("read_file", Files.readString(target, StandardCharsets.UTF_8));
    } catch (IOException | IllegalArgumentException exception) {
      return ToolResult.fail("read_file", "读取失败，请检查路径、文件类型、权限或UTF-8编码");
    }
  }

  /** 完整内容由调用方显式提供，不隐式创建父目录或重试写入. */
  @Tool(name = "write_file", description = "创建或覆盖UTF-8文件")
  public ToolResult writeFile(
      @ToolParam(description = "文件路径") String path,
      @ToolParam(description = "完整文件内容，空字符串表示清空") String content) {
    if (content == null) {
      return ToolResult.fail("write_file", "文件内容不能为空引用");
    }
    try {
      Path target = checkedPath(path);
      Files.writeString(target, content, StandardCharsets.UTF_8);
      return ToolResult.ok("write_file", "文件已写入");
    } catch (IOException | IllegalArgumentException exception) {
      return ToolResult.fail("write_file", "写入失败，请检查父目录、文件类型或权限");
    }
  }

  /** 排序后的直接子项让模型得到稳定且不递归扩权的目录视图. */
  @Tool(name = "list_dir", description = "列出目录直接子项")
  public ToolResult listDir(@ToolParam(description = "目录路径") String path) {
    try {
      Path target = checkedPath(path);
      try (var entries = Files.list(target)) {
        var names = entries.map(FileTools::entryName).sorted().toList();
        return ToolResult.ok("list_dir", mapper.writeValueAsString(names));
      }
    } catch (IOException | IllegalArgumentException | java.io.UncheckedIOException exception) {
      return ToolResult.fail("list_dir", "列目录失败，请检查目录类型或权限");
    }
  }

  private static String entryName(Path entry) {
    Path name = entry.getFileName();
    if (name == null) {
      throw new IllegalArgumentException("目录项缺少文件名");
    }
    return name.toString();
  }

  private Path checkedPath(String path) {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("路径不能为空");
    }
    Path target = Path.of(path).toAbsolutePath().normalize();
    // 规范化不访问磁盘，所有Files操作都必须留到许可检查之后。
    sandbox.enforce(new SandboxAction(ActionType.FILE_ACCESS, target.toString()));
    return target;
  }
}
