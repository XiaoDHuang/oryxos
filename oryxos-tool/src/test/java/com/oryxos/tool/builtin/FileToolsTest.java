package com.oryxos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;

import com.oryxos.tool.sandbox.ActionType;
import com.oryxos.tool.sandbox.FileSandboxProperties;
import com.oryxos.tool.sandbox.HttpSandboxProperties;
import com.oryxos.tool.sandbox.PermissiveSandbox;
import com.oryxos.tool.sandbox.Sandbox;
import com.oryxos.tool.sandbox.SandboxAction;
import com.oryxos.tool.sandbox.SandboxViolationException;
import com.oryxos.tool.sandbox.ShellSandboxProperties;
import com.oryxos.tool.sandbox.WhitelistSandbox;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("文件工具与安全边界")
class FileToolsTest {
  @TempDir Path directory;
  private final FileTools tools = new FileTools(new PermissiveSandbox());

  @Test
  @DisplayName("UTF8写入读取与显式空字符串覆盖")
  void readsWritesAndClears() throws Exception {
    Path file = directory.resolve("日志.txt");
    assertTrue(tools.writeFile(file.toString(), "你好🌟").success());
    assertEquals("你好🌟", tools.readFile(file.toString()).content());
    assertTrue(tools.writeFile(file.toString(), "").success());
    assertEquals("", Files.readString(file));
  }

  @Test
  @DisplayName("目录只返回排序后的直接子项名称")
  void listsSortedImmediateChildren() throws Exception {
    Files.createDirectories(directory.resolve("a/nested"));
    Files.writeString(directory.resolve("z.txt"), "");
    assertEquals("[\"a\",\"z.txt\"]", tools.listDir(directory.toString()).content());
  }

  @Test
  @DisplayName("缺失路径错误类型和非法编码明确失败")
  void reportsFilesystemErrors() throws Exception {
    Path invalid = directory.resolve("invalid.txt");
    Files.write(invalid, new byte[] {(byte) 0xff});
    assertFalse(tools.readFile(invalid.toString()).success());
    assertFalse(tools.readFile(directory.resolve("missing").toString()).success());
    assertFalse(tools.readFile(directory.toString()).success());
    assertFalse(tools.listDir(invalid.toString()).success());
    assertFalse(tools.writeFile(directory.toString(), "x").success());
  }

  @Test
  @DisplayName("写入不隐式创建父目录且失败不可重试")
  void doesNotCreateParents() {
    var result = tools.writeFile(directory.resolve("missing/file").toString(), "x");
    assertFalse(result.success());
    assertFalse(result.retryable());
    assertFalse(Files.exists(directory.resolve("missing")));
  }

  @Test
  @DisplayName("拒绝发生在任何文件读写查询之前")
  void refusesBeforeFilesystemAccess() {
    Sandbox deny =
        action -> {
          throw new SandboxViolationException("禁止访问");
        };
    FileTools denied = new FileTools(deny);
    try (var filesystem = mockStatic(Files.class)) {
      assertThrows(SandboxViolationException.class, () -> denied.readFile(directory.toString()));
      assertThrows(
          SandboxViolationException.class, () -> denied.writeFile(directory.toString(), "x"));
      assertThrows(SandboxViolationException.class, () -> denied.listDir(directory.toString()));
      filesystem.verifyNoInteractions();
    }
  }

  @Test
  @DisplayName("沙箱收到规范化绝对路径且权限错误脱敏")
  void normalizesAndSanitizes() {
    List<SandboxAction> actions = new ArrayList<>();
    Path path = directory.resolve("a/../secret-token").toAbsolutePath().normalize();
    FileTools observed = new FileTools(actions::add);
    try (var filesystem = mockStatic(Files.class)) {
      filesystem
          .when(() -> Files.readString(path, StandardCharsets.UTF_8))
          .thenThrow(new AccessDeniedException("secret-token"));
      var result = observed.readFile(directory.resolve("a/../secret-token").toString());
      assertFalse(result.success());
      assertFalse(result.errorMessage().contains("secret-token"));
      assertEquals(List.of(new SandboxAction(ActionType.FILE_ACCESS, path.toString())), actions);
    }
  }

  @Test
  @DisplayName("空路径空内容引用等非法输入不进入安全或IO链")
  void rejectsInvalidInput() {
    Sandbox sandbox = mock(Sandbox.class);
    FileTools guarded = new FileTools(sandbox);
    assertFalse(guarded.readFile(" ").success());
    assertFalse(guarded.writeFile(directory.resolve("x").toString(), null).success());
    assertFalse(guarded.listDir(null).success());
    verifyNoInteractions(sandbox);
  }

  @Test
  @DisplayName("真实白名单外读写被拦且文件系统零副作用")
  void whitelistDeniesOutsideRootWithoutTouchingFilesystem() throws Exception {
    WhitelistSandbox whitelist =
        new WhitelistSandbox(
            new FileSandboxProperties(List.of(directory.toString())),
            new ShellSandboxProperties(List.of()),
            new HttpSandboxProperties(List.of()));
    FileTools guarded = new FileTools(whitelist);
    Path outside = Files.createTempFile("oryxos-outside", ".txt");
    Path missing = directory.resolve("..").resolve("must-not-exist.txt").normalize();
    try {
      assertThrows(SandboxViolationException.class, () -> guarded.readFile(outside.toString()));
      assertThrows(
          SandboxViolationException.class, () -> guarded.writeFile(outside.toString(), "x"));
      assertEquals("", Files.readString(outside));
      assertThrows(
          SandboxViolationException.class, () -> guarded.writeFile(missing.toString(), "x"));
      assertFalse(Files.exists(missing));
    } finally {
      Files.deleteIfExists(outside);
    }
  }
}
