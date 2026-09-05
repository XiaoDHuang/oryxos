package com.oryxos.core.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileLoaderTest {

  private static final Set<String> GLOBAL_PROVIDERS = Set.of("deepseek", "kimi");

  @TempDir Path profilesDir;

  private final ProfileLoader loader = new ProfileLoader(GLOBAL_PROVIDERS);

  @Test
  @DisplayName("合法YAML_全字段解析")
  void validYaml_parsesAllFields() throws IOException {
    write(
        "ops-agent.yaml",
        """
        name: ops-agent
        description: Ops assistant
        identity:
          agent_name: Oryx
          prompt: You are an ops agent.
        provider:
          name: deepseek
          model: deepseek-chat
          temperature: 0.7
        tools: [read_file, shell]
        skills: [daily-digest]
        mcp_servers: [github]
        channels: [cli]
        notify_channels:
          - type: webhook
            url: "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=demo"
        schedules:
          - id: morning-report
            cron: "0 0 9 * * *"
            zone: "Asia/Shanghai"
            message: "生成昨日运维日报"
        bootstrap: [AGENTS.md, SOUL.md]
        settings:
          max_iterations: 5
          max_history_turns: 30
        created_at: "2026-08-23T00:00:00Z"
        updated_at: "2026-08-23T01:00:00Z"
        """);

    List<Profile> profiles = loader.loadAll(profilesDir);

    assertThat(profiles).hasSize(1);
    Profile profile = profiles.get(0);
    assertThat(profile.name()).isEqualTo("ops-agent");
    assertThat(profile.description()).isEqualTo("Ops assistant");
    assertThat(profile.identity().agentName()).isEqualTo("Oryx");
    assertThat(profile.identity().prompt()).isEqualTo("You are an ops agent.");
    assertThat(profile.provider().name()).isEqualTo("deepseek");
    assertThat(profile.provider().model()).isEqualTo("deepseek-chat");
    assertThat(profile.provider().temperature()).isEqualTo(0.7);
    assertThat(profile.tools()).containsExactly("read_file", "shell");
    assertThat(profile.skills()).containsExactly("daily-digest");
    assertThat(profile.mcpServers()).containsExactly("github");
    assertThat(profile.channels()).containsExactly("cli");
    assertThat(profile.notifyChannels()).hasSize(1);
    assertThat(profile.notifyChannels().get(0)).containsEntry("type", "webhook");
    assertThat(profile.notifyChannels().get(0)).containsKey("url");
    assertThat(profile.schedules()).hasSize(1);
    assertThat(profile.schedules().get(0).id()).isEqualTo("morning-report");
    assertThat(profile.schedules().get(0).cron()).isEqualTo("0 0 9 * * *");
    assertThat(profile.schedules().get(0).zone()).isEqualTo("Asia/Shanghai");
    assertThat(profile.schedules().get(0).message()).isEqualTo("生成昨日运维日报");
    assertThat(profile.bootstrap()).containsExactly("AGENTS.md", "SOUL.md");
    assertThat(profile.settings().maxIterations()).isEqualTo(5);
    assertThat(profile.settings().maxHistoryTurns()).isEqualTo(30);
    assertThat(profile.createdAt()).isEqualTo("2026-08-23T00:00:00Z");
    assertThat(profile.updatedAt()).isEqualTo("2026-08-23T01:00:00Z");
  }

  @Test
  @DisplayName("引用不存在的provider_报错清晰且不注册")
  void unknownProvider_isReportedAndSkipped() throws IOException {
    write(
        "bad-provider.yaml",
        """
        name: bad-provider
        provider:
          name: qwen
          model: qwen-max
        """);
    write(
        "good.yaml",
        """
        name: good
        provider:
          name: kimi
          model: kimi-k2
        """);

    List<Profile> profiles = loader.loadAll(profilesDir);

    assertThat(profiles).hasSize(1);
    assertThat(profiles.get(0).name()).isEqualTo("good");
  }

  @Test
  @DisplayName("坏文件不阻断其余加载")
  void brokenFile_doesNotBlockOthers() throws IOException {
    write("broken.yaml", "name: broken\n  bad-indent: [unclosed\n: ::");
    write(
        "fine.yaml",
        """
        name: fine
        provider:
          name: deepseek
          model: deepseek-chat
        """);

    List<Profile> profiles = loader.loadAll(profilesDir);

    assertThat(profiles).hasSize(1);
    assertThat(profiles.get(0).name()).isEqualTo("fine");
  }

  @Test
  @DisplayName("${ENV}占位从环境变量解析")
  void envPlaceholder_resolvesFromEnvironment() throws IOException {
    write(
        "env.yaml",
        """
        name: env-agent
        description: 'path is ${PATH}'
        provider:
          name: kimi
          model: kimi-k2
        """);

    List<Profile> profiles = loader.loadAll(profilesDir);

    assertThat(profiles).hasSize(1);
    assertThat(profiles.get(0).description()).isEqualTo("path is " + System.getenv("PATH"));
    assertThat(profiles.get(0).description()).doesNotContain("${PATH}");
  }

  @Test
  @DisplayName("缺省settings_按需求文档默认10与20")
  void missingSettings_appliesDefaults() throws IOException {
    write(
        "defaults.yaml",
        """
        name: defaults
        provider:
          name: deepseek
          model: deepseek-chat
        """);

    List<Profile> profiles = loader.loadAll(profilesDir);

    assertThat(profiles.get(0).settings().maxIterations()).isEqualTo(10);
    assertThat(profiles.get(0).settings().maxHistoryTurns()).isEqualTo(20);
  }

  @Test
  void missingDirectory_returnsEmpty() {
    assertThat(loader.loadAll(profilesDir.resolve("nope"))).isEmpty();
  }

  private void write(String fileName, String content) throws IOException {
    Files.writeString(profilesDir.resolve(fileName), content);
  }
}
