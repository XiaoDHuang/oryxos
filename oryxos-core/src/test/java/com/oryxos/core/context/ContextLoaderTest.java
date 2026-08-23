package com.oryxos.core.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oryxos.core.profile.Profile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextLoaderTest {

  @TempDir Path workspace;

  private ContextLoader loader;

  @BeforeEach
  void setUp() {
    loader = new ContextLoader(workspace);
  }

  @Test
  @DisplayName("改文件后下一次build立即读到新内容_无缓存")
  void fileChanged_nextLoadReadsNewContent() throws IOException {
    Files.writeString(workspace.resolve("SOUL.md"), "first version");
    Profile profile = profileWith(List.of("SOUL.md"), List.of());
    assertThat(loader.load(profile)).contains("first version");

    Files.writeString(workspace.resolve("SOUL.md"), "second version");

    assertThat(loader.load(profile)).contains("second version");
    assertThat(loader.load(profile)).doesNotContain("first version");
  }

  @Test
  @DisplayName("Skill引用缺失_报错")
  void missingSkill_throws() {
    Profile profile = profileWith(List.of(), List.of("daily-digest"));

    assertThatThrownBy(() -> loader.load(profile))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("daily-digest");
  }

  @Test
  @DisplayName("Bootstrap缺失_WARN跳过不报错")
  void missingBootstrap_warnsAndContinues() {
    Profile profile = profileWith(List.of("AGENTS.md"), List.of());

    String content = loader.load(profile);

    assertThat(content).doesNotContain("AGENTS.md");
  }

  @Test
  @DisplayName("Bootstrap与Skill都拼进上下文")
  void bootstrapAndSkill_bothIncluded() throws IOException {
    Files.writeString(workspace.resolve("AGENTS.md"), "behavior rules");
    Path skillDir = Files.createDirectories(workspace.resolve("skills/daily-digest"));
    Files.writeString(skillDir.resolve("SKILL.md"), "digest playbook");
    Profile profile = profileWith(List.of("AGENTS.md"), List.of("daily-digest"));

    String content = loader.load(profile);

    assertThat(content).contains("behavior rules").contains("digest playbook");
  }

  private static Profile profileWith(List<String> bootstrap, List<String> skills) {
    return new Profile(
        "ops", null, null, null, null, skills, null, null, null, null, bootstrap, null, null, null);
  }
}
