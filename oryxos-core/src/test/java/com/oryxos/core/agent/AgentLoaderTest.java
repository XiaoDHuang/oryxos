package com.oryxos.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oryxos.core.profile.Profile;
import com.oryxos.core.profile.ProfileRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** AgentLoader harness:frontmatter 与正文正确拆分、认出 scripts/skills/REFERENCE 资源、文件级错误与缺必填报错点名. */
class AgentLoaderTest {

  private static final Set<String> PROVIDERS = Set.of("deepseek");

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
      description: 每日订单对账 ${PATH_SUFFIX_CHECK}
      identity:
        agent_name: 对账小欧
        prompt: 严谨的对账助手
      provider:
        name: deepseek
        model: deepseek-chat
        temperature: 0.2
      tools: [shell, read_file]
      notify_channels:
        - type: webhook
          url: ${OPS_WEBHOOK_URL}
      ---

      你是每日订单对账助手。被触发时，严格按顺序做。
      第二行正文。
      """;

  @Test
  @DisplayName("正确拆出frontmatter与正文_键camelCase归一化_ENV占位解析")
  void loadSplitsFrontmatterAndBody() throws IOException {
    Path dir = agentDir("daily-reconcile", FULL_AGENT_MD.replace("${PATH_SUFFIX_CHECK}", ""));

    AgentDefinition definition = loader.load(dir);

    assertThat(definition.agentDir()).isEqualTo(dir);
    assertThat(definition.frontmatter()).containsEntry("name", "daily-reconcile");
    assertThat(definition.frontmatter()).containsKey("notifyChannels");
    assertThat(definition.body()).startsWith("你是每日订单对账助手。");
    assertThat(definition.body()).contains("第二行正文。");
    assertThat(definition.body()).doesNotContain("frontmatter");
    assertThat(definition.body()).doesNotContain("provider:");
  }

  @Test
  @DisplayName("ENV占位按进程环境解析_未设置保持原样")
  void loadResolvesEnvPlaceholders() throws IOException {
    Path dir =
        agentDir("daily-reconcile", FULL_AGENT_MD.replace("${PATH_SUFFIX_CHECK}", "${PATH}"));

    AgentDefinition definition = loader.load(dir);

    // 与 ProfileLoader 同规则:已设置的变量解析、未设置的占位符原样保留(不发明新语义)
    assertThat(definition.frontmatter().get("description").toString())
        .contains(System.getenv("PATH"));
    @SuppressWarnings("unchecked")
    var notifyChannels =
        (java.util.List<java.util.Map<String, Object>>)
            definition.frontmatter().get("notifyChannels");
    assertThat(notifyChannels.get(0)).containsEntry("url", "${OPS_WEBHOOK_URL}");
  }

  @Test
  @DisplayName("认出scripts/skills/REFERENCE资源_只记位置不读内容")
  void loadRecognizesResourcePaths() throws IOException {
    Path dir = agentDir("daily-reconcile", FULL_AGENT_MD.replace("${PATH_SUFFIX_CHECK}", ""));
    Files.createDirectories(dir.resolve("scripts"));
    Files.writeString(dir.resolve("scripts").resolve("reconcile.py"), "print('hi')");
    Files.createDirectories(dir.resolve("skills"));
    Files.writeString(dir.resolve("skills").resolve("report-format.md"), "# 规范");
    Files.writeString(dir.resolve("REFERENCE.md"), "# 参考");

    AgentDefinition definition = loader.load(dir);

    assertThat(definition.scriptsDir()).isEqualTo(dir.resolve("scripts"));
    assertThat(definition.skillsDir()).isEqualTo(dir.resolve("skills"));
    assertThat(definition.referenceFile()).isEqualTo(dir.resolve("REFERENCE.md"));
  }

  @Test
  @DisplayName("可选资源缺失时对应位置为null")
  void loadMissingOptionalResourcesAreNull() throws IOException {
    Path dir = agentDir("plain", FULL_AGENT_MD.replace("${PATH_SUFFIX_CHECK}", ""));

    AgentDefinition definition = loader.load(dir);

    assertThat(definition.scriptsDir()).isNull();
    assertThat(definition.skillsDir()).isNull();
    assertThat(definition.referenceFile()).isNull();
  }

  @Test
  @DisplayName("缺AGENT.md主文件_报错点名目录")
  void loadMissingMainFileThrowsNamingDir() throws IOException {
    Path dir = Files.createDirectories(workspace.resolve("agents").resolve("empty-agent"));

    assertThatThrownBy(() -> loader.load(dir))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("AGENT.md")
        .hasMessageContaining("empty-agent");
  }

  @Test
  @DisplayName("缺frontmatter围栏_报错点名目录")
  void loadMissingFenceThrowsNamingDir() throws IOException {
    Path dir = agentDir("no-fence", "没有围栏的正文\n");

    assertThatThrownBy(() -> loader.load(dir))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("frontmatter")
        .hasMessageContaining("no-fence");
  }

  @Test
  @DisplayName("frontmatter坏YAML_报错点名目录")
  void loadBrokenYamlThrowsNamingDir() throws IOException {
    Path dir = agentDir("broken", "---\nname: [unclosed\n  bad-indent: x\n---\n\n正文\n");

    assertThatThrownBy(() -> loader.load(dir))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("broken");
  }

  @Test
  @DisplayName("缺name/provider_经统一校验报错点名(与手写YAML同一消息)")
  void missingRequiredFields_surfaceSharedValidationMessage() throws IOException {
    Path noName = agentDir("no-name", "---\nprovider:\n  name: deepseek\n---\n\n正文\n");
    Path ghostProvider =
        agentDir("ghost", "---\nname: ghost-agent\nprovider:\n  name: ghost\n---\n\n正文\n");
    ProfileRegistry registry = new ProfileRegistry(List.of(), PROVIDERS);

    Profile derivedNoName = loader.deriveProfile(noName);
    Profile derivedGhost = loader.deriveProfile(ghostProvider);

    assertThatThrownBy(() -> registry.register(derivedNoName))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("缺少必填字段 'name'");
    assertThatThrownBy(() -> registry.register(derivedGhost))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("provider 'ghost' 未在全局 provider 层声明");
  }
}
