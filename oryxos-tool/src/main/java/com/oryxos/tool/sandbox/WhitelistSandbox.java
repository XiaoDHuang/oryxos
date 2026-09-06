package com.oryxos.tool.sandbox;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 核心阶段唯一一档完整实现：三类动作按白名单路由校验,HTTP委托007既有严格实现. 名单启动期从配置载入, 运行期可经管理端点增删(内存覆盖语义:只影响本进程,重启回
 * application.yaml 基线——用户决议); 集合用并发结构,增删与正在执行的 enforce 互不阻塞。
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
          new CopyOnWriteArrayList<>(
              fileProps.allowedPaths().stream()
                  .map(Path::of)
                  .map(path -> path.normalize().toAbsolutePath())
                  .toList());
    } catch (InvalidPathException exception) {
      throw new IllegalArgumentException("file.allowed_paths存在无效路径项", exception);
    }
    this.allowedCommands = ConcurrentHashMap.newKeySet();
    this.allowedCommands.addAll(shellProps.allowedCommands());
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

  /** 返回生效文件根目录视图(规范化后的绝对路径文本快照). */
  public List<String> allowedPathView() {
    return allowedRoots.stream().map(Path::toString).toList();
  }

  /** 返回生效命令首 token 视图(快照). */
  public Set<String> allowedCommandView() {
    return Set.copyOf(allowedCommands);
  }

  /** 返回生效 HTTP 域名视图(快照,规范化形态与 enforce 同源). */
  public Set<String> allowedDomainView() {
    return httpDelegate.allowedHostView();
  }

  /** 运行期新增文件根目录. 与启动期同规规范化(绝对化);无效路径抛 IllegalArgumentException, 已在名单内返回 false(幂等)。 */
  public boolean allowPath(String rawPath) {
    Path normalized = normalizePathEntry(rawPath);
    return !allowedRoots.contains(normalized) && allowedRoots.add(normalized);
  }

  /** 运行期删除文件根目录;入参无法规范化按"不存在"处理,返回 false. */
  public boolean denyPath(String rawPath) {
    try {
      return allowedRoots.remove(normalizePathEntry(rawPath));
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  /** 运行期新增命令首 token. 只允许单 token(空白字符会让首 token 语义失真), 违规抛 IllegalArgumentException;已存在返回 false。 */
  public boolean allowCommand(String command) {
    if (command == null
        || command.isBlank()
        || !command.equals(command.trim())
        || command.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException("命令项须为单个首 token(不含空白): " + command);
    }
    return allowedCommands.add(command);
  }

  /** 运行期删除命令首 token;不存在返回 false. */
  public boolean denyCommand(String command) {
    return command != null && allowedCommands.remove(command);
  }

  /** 运行期新增 HTTP 域名;规范化失败抛 IllegalArgumentException(与启动期同规则),已存在返回 false. */
  public boolean allowDomain(String domain) {
    return httpDelegate.allowDomain(domain);
  }

  /** 运行期删除 HTTP 域名;入参无法规范化按"不存在"处理,返回 false. */
  public boolean denyDomain(String domain) {
    return httpDelegate.denyDomain(domain);
  }

  private static Path normalizePathEntry(String rawPath) {
    if (rawPath == null || rawPath.isBlank()) {
      throw new IllegalArgumentException("路径项不能为空");
    }
    try {
      return Path.of(rawPath).normalize().toAbsolutePath();
    } catch (InvalidPathException exception) {
      throw new IllegalArgumentException("无效路径项: " + rawPath, exception);
    }
  }
}
