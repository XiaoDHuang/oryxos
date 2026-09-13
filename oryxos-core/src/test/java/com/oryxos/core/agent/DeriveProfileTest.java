package com.oryxos.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.oryxos.core.profile.Profile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** DeriveProfile harness:frontmatter 各字段正确映射到 Profile;schedules 原样带进;promptFile 绑定目录主文件. */
class DeriveProfileTest {

  private final AgentLoader loader = new AgentLoader();

  @TempDir Path workspace;

  private Path agentDir(String name, String agentMd) throws IOException {
    Path dir = Files.createDirectories(workspace.resolve("agents").resolve(name));
    Files.writeString(dir.resolve("AGENT.md"), agentMd);
    return dir;
  }

  private static final String FULL_AGENT_MD =
      """
      ---
      name: daily-reconcile
      description: 每天核对两库订单
      identity:
        agent_name: 对账小欧
        prompt: 严谨的对账助手
      provider:
        name: deepseek
        model: deepseek-chat
        temperature: 0.2
      tools: [shell, read_file, notify, save_memory]
      notify_channels:
        - type: webhook
          url: ${OPS_WEBHOOK_URL}
      schedules:
        - {id: reconcile-morning, cron: "0 0 9 * * *", zone: Asia/Shanghai,
           message: 到点了，核对昨天的订单对账。}
      settings:
        max_iterations: 5
      ---

      你是每日订单对账助手。
      """;

  @Test
  @DisplayName("frontmatter各字段一一映射到Profile")
  void deriveMapsAllFields() throws IOException {
    Path dir = agentDir("daily-reconcile", FULL_AGENT_MD);

    Profile profile = loader.deriveProfile(dir);

    assertThat(profile.name()).isEqualTo("daily-reconcile");
    assertThat(profile.description()).isEqualTo("每天核对两库订单");
    assertThat(profile.identity().agentName()).isEqualTo("对账小欧");
    assertThat(profile.identity().prompt()).isEqualTo("严谨的对账助手");
    assertThat(profile.provider().name()).isEqualTo("deepseek");
    assertThat(profile.provider().model()).isEqualTo("deepseek-chat");
    assertThat(profile.provider().temperature()).isEqualTo(0.2);
    assertThat(profile.tools()).containsExactly("shell", "read_file", "notify", "save_memory");
    assertThat(profile.notifyChannels()).hasSize(1);
    assertThat(profile.notifyChannels().get(0)).containsEntry("type", "webhook");
    assertThat(profile.settings().maxIterations()).isEqualTo(5);
  }

  @Test
  @DisplayName("schedules原样带进派生Profile(定时来自Agent的直接证据)")
  void deriveCarriesSchedulesVerbatim() throws IOException {
    Path dir = agentDir("daily-reconcile", FULL_AGENT_MD);

    Profile profile = loader.deriveProfile(dir);

    assertThat(profile.schedules()).hasSize(1);
    assertThat(profile.schedules().get(0).id()).isEqualTo("reconcile-morning");
    assertThat(profile.schedules().get(0).cron()).isEqualTo("0 0 9 * * *");
    assertThat(profile.schedules().get(0).zone()).isEqualTo("Asia/Shanghai");
    assertThat(profile.schedules().get(0).message()).isEqualTo("到点了，核对昨天的订单对账。");
  }

  @Test
  @DisplayName("正文经promptFile绑定目录主文件_人格prompt保持内联")
  void deriveBindsBodyViaPromptFile() throws IOException {
    Path dir = agentDir("daily-reconcile", FULL_AGENT_MD);

    Profile profile = loader.deriveProfile(dir);

    assertThat(profile.identity().promptFile()).isEqualTo("agents/daily-reconcile/AGENT.md");
    assertThat(profile.identity().prompt()).isEqualTo("严谨的对账助手");
  }

  @Test
  @DisplayName("未知键忽略(与手写YAML同构)")
  void deriveIgnoresUnknownKeys() throws IOException {
    Path dir =
        agentDir(
            "extra",
            """
            ---
            name: extra
            provider: {name: deepseek}
            future_extension: whatever
            ---

            正文
            """);

    Profile profile = loader.deriveProfile(dir);

    assertThat(profile.name()).isEqualTo("extra");
    assertThat(profile.provider().name()).isEqualTo("deepseek");
  }
}
