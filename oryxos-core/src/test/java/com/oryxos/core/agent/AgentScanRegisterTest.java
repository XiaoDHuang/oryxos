package com.oryxos.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.oryxos.core.profile.Profile;
import com.oryxos.core.profile.ProfileRegistry;
import com.oryxos.core.react.AgentService;
import com.oryxos.core.schedule.AgentScheduler;
import com.oryxos.core.schedule.ScheduledTaskStore;
import com.oryxos.core.schedule.ScheduledTaskView;
import com.oryxos.core.session.SessionManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** AgentScanRegister harness:扫 N 个目录注册 N 个、带 schedules 的进调度器、坏目录点名跳过、同名跳过不覆盖、未注册工具告警不阻断. */
class AgentScanRegisterTest {

  private static final Set<String> PROVIDERS = Set.of("deepseek");

  private static final Set<String> TOOLS = Set.of("shell", "read_file", "notify", "save_memory");

  @TempDir Path workspace;

  private ProfileRegistry registry;
  private AgentScheduler scheduler;

  @BeforeEach
  void setUp() {
    registry = new ProfileRegistry(List.of(), PROVIDERS);
    ThreadPoolTaskScheduler taskScheduler = mock(ThreadPoolTaskScheduler.class);
    ScheduledTaskStore store = mock(ScheduledTaskStore.class);
    when(taskScheduler.schedule(any(Runnable.class), any(Trigger.class)))
        .thenReturn(mock(ScheduledFuture.class));
    when(store.register(any(), any(), any()))
        .thenAnswer(
            invocation ->
                new ScheduledTaskView(
                    ((com.oryxos.core.profile.ScheduleConfig) invocation.getArgument(1)).id(),
                    invocation.getArgument(0),
                    null,
                    null,
                    null,
                    true,
                    invocation.getArgument(2),
                    null,
                    null,
                    0));
    scheduler =
        new AgentScheduler(
            taskScheduler,
            Runnable::run,
            registry,
            mock(AgentService.class),
            mock(SessionManager.class),
            store);
  }

  private Path writeAgent(String name, String frontmatter) throws IOException {
    Path dir = Files.createDirectories(workspace.resolve("agents").resolve(name));
    Files.writeString(dir.resolve("AGENT.md"), "---\n" + frontmatter + "---\n\n正文\n");
    return dir;
  }

  private static String validFrontmatter(String name, String schedulesBlock) {
    return "name: "
        + name
        + "\nprovider:\n  name: deepseek\n  model: deepseek-chat\n"
        + schedulesBlock;
  }

  @Test
  @DisplayName("扫N个目录注册N个_带schedules的都进了调度器")
  void scanRegistersAllAgentsAndTheirSchedules() throws IOException {
    String scheduleA =
        "schedules:\n  - {id: task-a, cron: \"0 0 9 * * *\", zone: Asia/Shanghai, message: 早安}\n";
    String scheduleB =
        "schedules:\n  - {id: task-b, cron: \"0 30 18 * * *\", zone: UTC, message: 晚安}\n";
    writeAgent("agent-a", validFrontmatter("agent-a", scheduleA));
    writeAgent("agent-b", validFrontmatter("agent-b", scheduleB));
    writeAgent("agent-c", validFrontmatter("agent-c", ""));

    AgentDirectoryScanner scanner = new AgentDirectoryScanner(registry, TOOLS, scheduler);
    int registered = scanner.scan(workspace.resolve("agents"));

    assertThat(registered).isEqualTo(3);
    assertThat(registry.exists("agent-a")).isTrue();
    assertThat(registry.exists("agent-b")).isTrue();
    assertThat(registry.exists("agent-c")).isTrue();
    assertThat(scheduler.isRegistered("task-a")).isTrue();
    assertThat(scheduler.isRegistered("task-b")).isTrue();
  }

  @Test
  @DisplayName("缺必填或provider未声明的目录点名跳过_不阻断其余")
  void scanSkipsInvalidAgentsWithoutBlocking() throws IOException {
    writeAgent("bad-no-name", "provider:\n  name: deepseek\n");
    writeAgent("bad-ghost-provider", "name: ghost\nprovider:\n  name: ghost\n");
    writeAgent("good", validFrontmatter("good", ""));

    AgentDirectoryScanner scanner = new AgentDirectoryScanner(registry, TOOLS, scheduler);
    int registered = scanner.scan(workspace.resolve("agents"));

    assertThat(registered).isEqualTo(1);
    assertThat(registry.exists("good")).isTrue();
    assertThat(registry.all()).hasSize(1);
  }

  @Test
  @DisplayName("与既有注册项同名_跳过派生注册不覆盖")
  void scanSkipsDuplicateNameWithoutOverwriting() throws IOException {
    Profile handwritten =
        new Profile(
            "daily-reconcile",
            "手写原版",
            null,
            new Profile.Provider("deepseek", "m", null, null),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    registry.register(handwritten);
    writeAgent("daily-reconcile", validFrontmatter("daily-reconcile", "description: 目录版\n"));

    AgentDirectoryScanner scanner = new AgentDirectoryScanner(registry, TOOLS, scheduler);
    int registered = scanner.scan(workspace.resolve("agents"));

    assertThat(registered).isZero();
    assertThat(registry.find("daily-reconcile")).isPresent();
    assertThat(registry.find("daily-reconcile").get().description()).isEqualTo("手写原版");
  }

  @Test
  @DisplayName("引用未注册能力_告警不阻断注册")
  void scanWarnsUnregisteredToolButStillRegisters() throws IOException {
    writeAgent("agent-tools", validFrontmatter("agent-tools", "tools: [shell, ghost_tool]\n"));

    AgentDirectoryScanner scanner = new AgentDirectoryScanner(registry, TOOLS, scheduler);
    int registered = scanner.scan(workspace.resolve("agents"));

    assertThat(registered).isEqualTo(1);
    assertThat(registry.exists("agent-tools")).isTrue();
  }

  @Test
  @DisplayName("工具表不可判定(null)时不误报也不阻断")
  void scanWithUnknownToolTableSkipsWarning() throws IOException {
    writeAgent("agent-any-tools", validFrontmatter("agent-any-tools", "tools: [whatever]\n"));

    AgentDirectoryScanner scanner = new AgentDirectoryScanner(registry, null, scheduler);

    assertThat(scanner.scan(workspace.resolve("agents"))).isEqualTo(1);
    assertThat(registry.exists("agent-any-tools")).isTrue();
  }

  @Test
  @DisplayName("agents目录不存在返回零不报错")
  void scanMissingAgentsDirReturnsZero() {
    AgentDirectoryScanner scanner = new AgentDirectoryScanner(registry, TOOLS, scheduler);

    assertThat(scanner.scan(workspace.resolve("agents"))).isZero();
    assertThat(registry.all()).isEmpty();
  }

  @Test
  @DisplayName("无调度器模式(null)仍注册Agent_跳过定时注册")
  void scanWithoutSchedulerSkipsScheduling() throws IOException {
    writeAgent(
        "agent-scheduled",
        validFrontmatter(
            "agent-scheduled",
            "schedules:\n  - {id: task-x, cron: \"0 0 9 * * *\", zone: UTC, message: 到点}\n"));

    AgentDirectoryScanner scanner = new AgentDirectoryScanner(registry, TOOLS, null);

    assertThat(scanner.scan(workspace.resolve("agents"))).isEqualTo(1);
    assertThat(registry.exists("agent-scheduled")).isTrue();
    assertThat(registry.find("agent-scheduled").get().schedules()).hasSize(1);
  }

  @Test
  @DisplayName("子目录缺AGENT.md跳过_不阻断其余")
  void scanSkipsDirMissingMainFile() throws IOException {
    Files.createDirectories(workspace.resolve("agents").resolve("empty-shell"));
    writeAgent("good", validFrontmatter("good", ""));

    AgentDirectoryScanner scanner = new AgentDirectoryScanner(registry, TOOLS, scheduler);

    assertThat(scanner.scan(workspace.resolve("agents"))).isEqualTo(1);
    assertThat(registry.exists("good")).isTrue();
  }
}
