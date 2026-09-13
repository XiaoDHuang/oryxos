package com.oryxos.core.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oryxos.core.profile.Profile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ProgressiveDisclosure harness(29 节):正文进 prompt 且去 frontmatter;参考/子指令/脚本不预载;现读无缓存. */
class ProgressiveDisclosureTest {

  @TempDir Path workspace;

  private ContextLoader loader;

  private static Profile agentProfile(String promptFile) {
    return new Profile(
        "daily-reconcile",
        null,
        new Profile.Identity("对账小欧", "严谨的对账助手", promptFile),
        null,
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
  }

  private void writeAgentDir(String body) throws IOException {
    Path dir = Files.createDirectories(workspace.resolve("agents").resolve("daily-reconcile"));
    Files.writeString(
        dir.resolve("AGENT.md"),
        "---\nname: daily-reconcile\nprovider:\n  name: deepseek\n---\n\n" + body + "\n");
  }

  @Test
  @DisplayName("promptFile正文进上下文且不含frontmatter")
  void promptFileBodyInjectedWithoutFrontmatter() throws IOException {
    writeAgentDir("你是每日订单对账助手，严格按顺序做。");
    loader = new ContextLoader(workspace);

    String context = loader.load(agentProfile("agents/daily-reconcile/AGENT.md"));

    assertThat(context).contains("你是每日订单对账助手，严格按顺序做。");
    assertThat(context).doesNotContain("provider:");
    assertThat(context).doesNotContain("name: daily-reconcile");
    assertThat(context).doesNotContain("---");
  }

  @Test
  @DisplayName("参考/子指令/脚本不预载(渐进式披露守点)")
  void resourcesAreNotPreloaded() throws IOException {
    writeAgentDir("正文：用到才读参考。");
    Path dir = workspace.resolve("agents").resolve("daily-reconcile");
    Files.writeString(dir.resolve("REFERENCE.md"), "独门参考内容-不应预载");
    Files.createDirectories(dir.resolve("skills"));
    Files.writeString(dir.resolve("skills").resolve("report-format.md"), "独门子指令-不应预载");
    Files.createDirectories(dir.resolve("scripts"));
    Files.writeString(dir.resolve("scripts").resolve("reconcile.py"), "独门脚本代码-不应预载");
    loader = new ContextLoader(workspace);

    String context = loader.load(agentProfile("agents/daily-reconcile/AGENT.md"));

    assertThat(context).contains("正文：用到才读参考。");
    assertThat(context).doesNotContain("独门参考内容");
    assertThat(context).doesNotContain("独门子指令");
    assertThat(context).doesNotContain("独门脚本代码");
  }

  @Test
  @DisplayName("两次调用之间改文件_第二次即见新正文(无缓存,改正文不重启生效)")
  void fileChangeVisibleOnNextLoad() throws IOException {
    writeAgentDir("第一版说明。");
    loader = new ContextLoader(workspace);
    assertThat(loader.load(agentProfile("agents/daily-reconcile/AGENT.md"))).contains("第一版说明。");

    writeAgentDir("第二版说明。");
    String context = loader.load(agentProfile("agents/daily-reconcile/AGENT.md"));

    assertThat(context).contains("第二版说明。");
    assertThat(context).doesNotContain("第一版说明。");
  }

  @Test
  @DisplayName("promptFile缺失_显式失败(缺失人格仍继续会改变Agent行为)")
  void missingPromptFileFails() {
    loader = new ContextLoader(workspace);

    assertThatThrownBy(() -> loader.load(agentProfile("agents/ghost/AGENT.md")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("agents/ghost/AGENT.md");
  }

  @Test
  @DisplayName("无frontmatter的普通prompt文件原样注入")
  void plainPromptFileWithoutFrontmatterPassThrough() throws IOException {
    Files.writeString(workspace.resolve("PERSONA.md"), "纯人格文件，没有围栏。\n第二行。\n");
    loader = new ContextLoader(workspace);

    String context = loader.load(agentProfile("PERSONA.md"));

    assertThat(context).contains("纯人格文件，没有围栏。");
    assertThat(context).contains("第二行。");
  }

  @Test
  @DisplayName("未设promptFile的Profile行为不变")
  void profileWithoutPromptFileUnchanged() {
    loader = new ContextLoader(workspace);

    assertThat(loader.load(agentProfile(null))).isEmpty();
  }
}
