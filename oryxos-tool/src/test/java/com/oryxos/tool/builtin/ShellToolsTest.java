package com.oryxos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.oryxos.core.tool.ToolResult;
import com.oryxos.tool.sandbox.ActionType;
import com.oryxos.tool.sandbox.PermissiveSandbox;
import com.oryxos.tool.sandbox.Sandbox;
import com.oryxos.tool.sandbox.SandboxViolationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Shell工具的执行与进程回收")
class ShellToolsTest {

  @TempDir Path directory;

  @AfterEach
  void clearInterrupt() {
    Thread.interrupted();
  }

  @Test
  @DisplayName("完整命令作为bash的单个参数且合并输出")
  void preservesCommandAndMergedOutput() throws Exception {
    String command = "printf '%s' 'a b'; printf '%s' err >&2";
    Process process = process("a berr", 0);
    AtomicReference<ProcessBuilder> seen = new AtomicReference<>();
    ShellTools tools =
        new ShellTools(
            action -> {
              assertEquals(ActionType.SHELL_EXEC, action.type());
              assertEquals(command, action.target());
              assertNull(seen.get());
            },
            builder -> {
              seen.set(builder);
              return process;
            },
            ShellTools.EXECUTION_TIMEOUT);
    var result = tools.shell(command);
    assertTrue(result.success());
    assertEquals("a berr", result.content());
    assertEquals(List.of("bash", "-c", command), seen.get().command());
    assertTrue(seen.get().redirectErrorStream());
    assertEquals(Duration.ofSeconds(30), ShellTools.EXECUTION_TIMEOUT);
    verify(process.getInputStream()).close();
    verify(process.getErrorStream()).close();
    verify(process.getOutputStream(), atLeastOnce()).close();
  }

  @Test
  @DisplayName("非零退出包含退出码及有界输出且不自动重试")
  void reportsExitFailure() throws Exception {
    Process process = process("错误输出", 7);
    var result =
        new ShellTools(new PermissiveSandbox(), ignored -> process, Duration.ofSeconds(1))
            .shell("exit 7");
    assertFalse(result.success());
    assertFalse(result.retryable());
    assertTrue(result.errorMessage().contains("7"));
    assertTrue(result.errorMessage().contains("错误输出"));
  }

  @Test
  @DisplayName("拒绝和非法命令零进程启动")
  void deniesBeforeStarting() {
    @SuppressWarnings("unchecked")
    Function<ProcessBuilder, Process> starter = mock(Function.class);
    Sandbox sandbox =
        action -> {
          throw new SandboxViolationException("禁止命令");
        };
    ShellTools tools = new ShellTools(sandbox, starter, Duration.ofSeconds(1));
    assertThrows(SandboxViolationException.class, () -> tools.shell("echo test"));
    assertFalse(tools.shell(" ").success());
    verifyNoInteractions(starter);
  }

  @Test
  @DisplayName("无bash时明确失败且不切换命令语言")
  void missingBashFailsSafely() {
    var result =
        new ShellTools(
                new PermissiveSandbox(),
                builder -> {
                  assertEquals("bash", builder.command().getFirst());
                  throw new UncheckedIOException(new IOException("secret-path"));
                },
                Duration.ofSeconds(1))
            .shell("echo test");
    assertFalse(result.success());
    assertFalse(result.retryable());
    assertTrue(result.errorMessage().contains("bash"));
    assertFalse(result.errorMessage().contains("secret-path"));
  }

  @Test
  @DisplayName("捕获不超过1MiB且超限停止进程")
  void boundsOutput() throws Exception {
    Process exact = process("x".repeat(ShellTools.MAX_OUTPUT_BYTES), 0);
    assertEquals(
        ShellTools.MAX_OUTPUT_BYTES,
        new ShellTools(new PermissiveSandbox(), ignored -> exact, Duration.ofSeconds(1))
            .shell("x")
            .content()
            .length());
    Process oversized = process("x".repeat(ShellTools.MAX_OUTPUT_BYTES + 1), 0);
    var result =
        new ShellTools(new PermissiveSandbox(), ignored -> oversized, Duration.ofSeconds(1))
            .shell("x");
    assertFalse(result.success());
    assertFalse(result.retryable());
    verify(oversized, atLeastOnce()).destroyForcibly();
  }

  @Test
  @DisplayName("进程退出时available短暂为零仍保留随后可见的尾部输出")
  void retainsLateVisibleTailAfterExit() throws Exception {
    Process process = process("", 0);
    var delayed = new DelayedAvailableInputStream("tail".getBytes(StandardCharsets.UTF_8), 2);
    when(process.getInputStream()).thenReturn(delayed);
    var result =
        new ShellTools(new PermissiveSandbox(), ignored -> process, Duration.ofSeconds(1))
            .shell("printf tail");
    assertTrue(result.success(), result.errorMessage());
    assertEquals("tail", result.content());
  }

  @Test
  @DisplayName("等待超时会销毁父进程与已发现子进程")
  void destroysOnTimeout() throws Exception {
    Process process = process("", 0);
    ProcessHandle child = mock(ProcessHandle.class);
    when(child.isAlive()).thenReturn(true, false);
    when(process.descendants()).thenAnswer(ignored -> Stream.of(child));
    when(process.waitFor(anyLong(), any())).thenReturn(false);
    var result =
        new ShellTools(new PermissiveSandbox(), ignored -> process, Duration.ofMillis(20))
            .shell("sleep 30");
    assertFalse(result.success());
    assertTrue(result.errorMessage().contains("超时"));
    verify(process, atLeastOnce()).destroyForcibly();
    verify(child, atLeastOnce()).destroyForcibly();
  }

  @Test
  @DisplayName("中断保留标志并销毁已启动进程")
  void destroysOnInterruption() throws Exception {
    Process process = process("", 0);
    when(process.waitFor(anyLong(), any())).thenThrow(new InterruptedException());
    var result =
        new ShellTools(new PermissiveSandbox(), ignored -> process, Duration.ofSeconds(1))
            .shell("sleep 30");
    assertFalse(result.success());
    assertTrue(Thread.currentThread().isInterrupted());
    verify(process, atLeastOnce()).destroyForcibly();
  }

  @Test
  @Tag("integration")
  @DisplayName("真实bash合并输出并在短预算超时后回收进程管道")
  void realProcessLifecycle() throws Exception {
    AtomicReference<Process> process = new AtomicReference<>();
    AtomicReference<List<ProcessHandle>> observedChildren = new AtomicReference<>(List.of());
    String executable = testBash();
    Function<ProcessBuilder, Process> start =
        builder -> {
          try {
            // Windows的CreateProcess优先找到System32的WSL入口，测试显式选PATH中的真实bash。
            builder.command().set(0, executable);
            Process child = builder.start();
            process.set(child);
            if (builder.command().getLast().contains("& wait")) {
              long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
              while (observedChildren.get().isEmpty() && System.nanoTime() < deadline) {
                observedChildren.set(child.descendants().toList());
                Thread.sleep(10);
              }
            }
            return child;
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试进程启动已中断");
          } catch (IOException exception) {
            throw new UncheckedIOException(exception);
          }
        };
    ShellTools normal = new ShellTools(new PermissiveSandbox(), start, Duration.ofSeconds(5));
    ToolResult result = normal.shell("printf '你好'; printf '错误' >&2");
    assertTrue(result.success(), result.errorMessage());
    assertEquals("你好错误", result.content());
    assertFalse(process.get().isAlive());
    ToolResult failed = normal.shell("printf '实际错误' >&2; exit 7");
    assertFalse(failed.success());
    assertTrue(failed.errorMessage().contains("7"));
    assertTrue(failed.errorMessage().contains("实际错误"));
    ShellTools limited = new ShellTools(new PermissiveSandbox(), start, Duration.ofMillis(300));
    long started = System.nanoTime();
    var timeout = limited.shell("sleep 30 & wait");
    assertFalse(timeout.success());
    assertTrue(timeout.errorMessage().contains("超时"));
    assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0);
    assertFalse(process.get().isAlive());
    assertFalse(observedChildren.get().isEmpty(), "应实际观察到bash子进程");
    assertTrue(observedChildren.get().stream().noneMatch(ProcessHandle::isAlive));
  }

  @Test
  @Tag("integration")
  @DisplayName("真实bash等待中断后保留标志并回收进程")
  void realProcessInterruption() throws Exception {
    CountDownLatch waiting = new CountDownLatch(1);
    AtomicReference<Process> child = new AtomicReference<>();
    AtomicReference<ToolResult> result = new AtomicReference<>();
    AtomicBoolean interrupted = new AtomicBoolean();
    ShellTools tools =
        new ShellTools(
            new PermissiveSandbox(),
            builder -> {
              try {
                builder.command().set(0, testBash());
                Process actual = builder.start();
                child.set(actual);
                Process observed = mock(Process.class, delegatesTo(actual));
                doAnswer(
                        invocation -> {
                          waiting.countDown();
                          return actual.waitFor(
                              invocation.getArgument(0), invocation.getArgument(1));
                        })
                    .when(observed)
                    .waitFor(anyLong(), any(TimeUnit.class));
                return observed;
              } catch (IOException exception) {
                throw new UncheckedIOException(exception);
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("测试等待已中断");
              }
            },
            Duration.ofSeconds(10));
    Thread caller =
        Thread.ofVirtual()
            .start(
                () -> {
                  result.set(tools.shell("sleep 30 & wait"));
                  interrupted.set(Thread.currentThread().isInterrupted());
                });
    try {
      assertTrue(waiting.await(5, TimeUnit.SECONDS));
      caller.interrupt();
      assertTrue(caller.join(Duration.ofSeconds(5)));
      assertFalse(result.get().success());
      assertTrue(result.get().errorMessage().contains("中断"));
      assertTrue(interrupted.get());
      assertFalse(child.get().isAlive());
    } finally {
      caller.interrupt();
      if (child.get() != null) {
        child.get().descendants().forEach(ProcessHandle::destroyForcibly);
        child.get().destroyForcibly();
      }
      caller.join(Duration.ofSeconds(5));
    }
  }

  private static String testBash() {
    if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")) {
      return "bash";
    }
    return Arrays.stream(System.getenv("PATH").split(Pattern.quote(File.pathSeparator)))
        .map(Path::of)
        .map(path -> path.resolve("bash.exe"))
        .filter(Files::isRegularFile)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("测试环境需要真实bash"))
        .toString();
  }

  private static Process process(String output, int exitCode) throws Exception {
    Process process = mock(Process.class);
    when(process.getInputStream())
        .thenReturn(spy(new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8))));
    when(process.getErrorStream()).thenReturn(spy(new ByteArrayInputStream(new byte[0])));
    when(process.getOutputStream()).thenReturn(spy(new ByteArrayOutputStream()));
    when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
    when(process.exitValue()).thenReturn(exitCode);
    when(process.descendants()).thenAnswer(ignored -> Stream.empty());
    return process;
  }

  private static final class DelayedAvailableInputStream extends ByteArrayInputStream {
    private int emptyProbes;

    private DelayedAvailableInputStream(byte[] bytes, int emptyProbes) {
      super(bytes);
      this.emptyProbes = emptyProbes;
    }

    @Override
    public synchronized int available() {
      if (emptyProbes > 0) {
        emptyProbes--;
        return 0;
      }
      return super.available();
    }
  }
}
