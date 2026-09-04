package com.oryxos.tool.sandbox;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 核心阶段唯一一档完整实现：三类动作按白名单路由校验,HTTP委托007既有严格实现.
 *
 * @author OryxOS Contributors
 */
public final class WhitelistSandbox implements Sandbox {

  private final List<Path> allowedRoots;
  private final Set<String> allowedCommands;
  private final HttpWhitelistSandbox httpDelegate;

  /** 名单在启动期固化：两侧同基准绝对化后,相对路径配置的默认工作区才能命中绝对目标. */
  public WhitelistSandbox(
      FileSandboxProperties fileProps,
      ShellSandboxProperties shellProps,
      HttpSandboxProperties httpProps) {
    try {
      this.allowedRoots =
          fileProps.allowedPaths().stream()
              .map(Path::of)
              .map(path -> path.normalize().toAbsolutePath())
              .toList();
    } catch (InvalidPathException exception) {
      throw new IllegalArgumentException("file.allowed_paths存在无效路径项", exception);
    }
    this.allowedCommands = new LinkedHashSet<>(shellProps.allowedCommands());
    this.httpDelegate = new HttpWhitelistSandbox(httpProps.allowedDomains());
  }

  @Override
  public void enforce(SandboxAction action) {
    switch (action.type()) {
      case FILE_ACCESS:
        checkFilePath(action.target());
        break;
      case SHELL_EXEC:
        checkShellCommand(action.target());
        break;
      case HTTP_REQUEST:
        httpDelegate.enforce(action);
        break;
      default:
        throw new SandboxViolationException("未知安全动作类型");
    }
  }

  private void checkFilePath(String rawPath) {
    Path target;
    try {
      target = Path.of(rawPath).normalize().toAbsolutePath();
    } catch (InvalidPathException exception) {
      throw new SandboxViolationException("路径不在白名单内: " + rawPath);
    }
    boolean allowed = allowedRoots.stream().anyMatch(target::startsWith);
    if (!allowed) {
      throw new SandboxViolationException("路径不在白名单内: " + rawPath);
    }
  }

  private void checkShellCommand(String command) {
    String firstToken = command.trim().split("\\s+")[0];
    if (!allowedCommands.contains(firstToken)) {
      throw new SandboxViolationException("命令不在白名单内: " + firstToken);
    }
  }
}
