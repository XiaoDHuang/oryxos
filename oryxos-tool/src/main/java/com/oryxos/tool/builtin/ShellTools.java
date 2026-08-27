package com.oryxos.tool.builtin;

import com.oryxos.core.tool.ToolResult;
import com.oryxos.tool.sandbox.ActionType;
import com.oryxos.tool.sandbox.Sandbox;
import com.oryxos.tool.sandbox.SandboxAction;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Shell仅在许可后同步执行，生命周期与输出必须有界.
 *
 * @author OryxOS Contributors
 */
public final class ShellTools {
  static final Duration EXECUTION_TIMEOUT = Duration.ofSeconds(30);
  static final int MAX_OUTPUT_BYTES = 1024 * 1024;

  private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(1);
  private static final long OBSERVATION_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
  private static final int OUTPUT_SETTLE_POLLS = 3;
  private static final Logger LOG = LoggerFactory.getLogger(ShellTools.class);

  private final Sandbox sandbox;
  private final Function<ProcessBuilder, Process> starter;
  private final Duration timeout;

  /** 生产固定使用bash及30秒时限，不引入命令语言或超时配置开关. */
  public ShellTools(Sandbox sandbox) {
    this(
        sandbox,
        builder -> {
          try {
            return builder.start();
          } catch (IOException exception) {
            throw new UncheckedIOException(exception);
          }
        },
        EXECUTION_TIMEOUT);
  }

  ShellTools(Sandbox sandbox, Function<ProcessBuilder, Process> starter, Duration timeout) {
    this.sandbox = Objects.requireNonNull(sandbox);
    this.starter = Objects.requireNonNull(starter);
    this.timeout = Objects.requireNonNull(timeout);
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("命令执行时限必须为正数");
    }
  }

  /** 原始命令整体交给bash解释，执行与输出资源必须在同一调用内收尾. */
  @SuppressFBWarnings(
      value = "COMMAND_INJECTION",
      justification = "Shell工具按契约执行原始bash -c；调用前Sandbox强制校验且生产默认拒绝，真实白名单归DR-002")
  @Tool(name = "shell", description = "使用bash执行命令，最多30秒并限制输出大小")
  public ToolResult shell(@ToolParam(description = "交给bash的完整原始命令") String command) {
    if (command == null || command.isBlank()) {
      return ToolResult.fail("shell", "命令不能为空");
    }
    if (Thread.currentThread().isInterrupted()) {
      return ToolResult.fail("shell", "命令执行已中断");
    }
    sandbox.enforce(new SandboxAction(ActionType.SHELL_EXEC, command));
    Process process;
    try {
      process = starter.apply(new ProcessBuilder("bash", "-c", command).redirectErrorStream(true));
    } catch (UncheckedIOException exception) {
      return ToolResult.fail("shell", "无法启动bash，请检查安装与执行权限");
    }
    Set<ProcessHandle> descendants = new LinkedHashSet<>();
    AtomicReference<byte[]> output = new AtomicReference<>();
    AtomicBoolean readFailed = new AtomicBoolean();
    AtomicBoolean oversized = new AtomicBoolean();
    long deadline = System.nanoTime() + timeout.toNanos();
    Thread reader = outputReader(process, output, readFailed, oversized);
    ToolResult result;
    boolean cleaned;
    try {
      process.getOutputStream().close();
      reader.start();
      boolean exited = waitForExit(process, descendants, deadline);
      long remaining = deadline - System.nanoTime();
      if (!exited || remaining <= 0 || !reader.join(Duration.ofNanos(remaining))) {
        result = ToolResult.fail("shell", "命令执行超时，已请求回收进程");
      } else if (oversized.get()) {
        result = ToolResult.fail("shell", "命令输出超过1MiB上限");
      } else if (readFailed.get() || output.get() == null) {
        result = ToolResult.fail("shell", "命令输出读取失败");
      } else {
        String text = new String(output.get(), StandardCharsets.UTF_8);
        int exitCode = process.exitValue();
        result =
            exitCode == 0
                ? ToolResult.ok("shell", text)
                : ToolResult.fail("shell", "命令退出码=" + exitCode + "，输出：" + text);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      result = ToolResult.fail("shell", "命令执行已中断");
    } catch (IOException | RuntimeException exception) {
      result = ToolResult.fail("shell", "命令执行或输出处理失败");
    } finally {
      cleaned = cleanup(process, descendants, reader);
    }
    return cleaned ? result : ToolResult.fail("shell", "命令结束但资源回收未完成");
  }

  private static Thread outputReader(
      Process process,
      AtomicReference<byte[]> output,
      AtomicBoolean readFailed,
      AtomicBoolean oversized) {
    return Thread.ofVirtual()
        .name("oryxos-shell-output")
        .unstarted(
            () -> {
              ByteArrayOutputStream captured = new ByteArrayOutputStream();
              int emptyAfterExit = 0;
              try {
                while (!Thread.currentThread().isInterrupted()) {
                  // Windows管道的阻塞read会让close等待；只取已到达字节，取消时可及时退出。
                  int available = process.getInputStream().available();
                  if (available > 0) {
                    emptyAfterExit = 0;
                    int amount = Math.min(available, MAX_OUTPUT_BYTES + 1 - captured.size());
                    captured.writeBytes(process.getInputStream().readNBytes(amount));
                    if (captured.size() > MAX_OUTPUT_BYTES) {
                      oversized.set(true);
                      process.destroyForcibly();
                      break;
                    }
                  } else if (!process.isAlive()) {
                    emptyAfterExit++;
                    if (emptyAfterExit >= OUTPUT_SETTLE_POLLS) {
                      break;
                    }
                    Thread.sleep(10);
                  } else {
                    emptyAfterExit = 0;
                    Thread.sleep(10);
                  }
                }
              } catch (IOException exception) {
                readFailed.set(process.isAlive());
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
              } finally {
                output.set(captured.toByteArray());
              }
            });
  }

  private static boolean waitForExit(Process process, Set<ProcessHandle> descendants, long deadline)
      throws InterruptedException {
    while (true) {
      // 父进程退出后无法再枚举其子进程，等待期间先保存可见句柄供清理使用。
      descendants.addAll(process.descendants().toList());
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        return false;
      }
      if (process.waitFor(Math.min(remaining, OBSERVATION_INTERVAL_NANOS), TimeUnit.NANOSECONDS)) {
        descendants.addAll(process.descendants().toList());
        return true;
      }
    }
  }

  private static boolean cleanup(Process process, Set<ProcessHandle> descendants, Thread reader) {
    boolean interrupted = Thread.interrupted();
    try {
      descendants.addAll(process.descendants().toList());
      for (ProcessHandle child : descendants) {
        if (child.isAlive()) {
          child.destroyForcibly();
        }
      }
      process.destroyForcibly();
      reader.interrupt();
      reader.join(CLEANUP_TIMEOUT);
      close(process.getInputStream());
      close(process.getErrorStream());
      close(process.getOutputStream());
      process.waitFor(CLEANUP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      long deadline = System.nanoTime() + CLEANUP_TIMEOUT.toNanos();
      while (descendants.stream().anyMatch(ProcessHandle::isAlive)
          && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      boolean cleaned =
          !process.isAlive()
              && !reader.isAlive()
              && descendants.stream().noneMatch(ProcessHandle::isAlive);
      if (!cleaned) {
        LOG.warn("命令资源未在清理时限内全部退出");
      }
      return cleaned;
    } catch (InterruptedException exception) {
      interrupted = true;
      return !process.isAlive() && !reader.isAlive();
    } catch (RuntimeException exception) {
      LOG.warn("命令资源回收失败，异常类别={}", exception.getClass().getSimpleName());
      return false;
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static void close(Closeable stream) {
    try {
      stream.close();
    } catch (IOException exception) {
      LOG.warn("命令管道关闭失败");
    }
  }
}
