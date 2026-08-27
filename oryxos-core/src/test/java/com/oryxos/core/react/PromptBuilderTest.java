package com.oryxos.core.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.oryxos.core.context.ContextLoader;
import com.oryxos.core.profile.Profile;
import com.oryxos.core.prompt.Prompt;
import com.oryxos.core.session.Session;
import com.oryxos.core.tool.OryxTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

class PromptBuilderTest {

  @TempDir Path workspace;

  private PromptBuilder promptBuilder;

  @BeforeEach
  void setUp() {
    promptBuilder = new PromptBuilder(new ContextLoader(workspace), Map.of());
  }

  @Test
  @DisplayName("四部分顺序_system在首位_历史随后_工具列表随身")
  void fourPartsInOrder() throws IOException {
    Files.writeString(workspace.resolve("SOUL.md"), "soul persona");
    Session session = new Session("s-1", "ops");
    session.append(new UserMessage("hello"));

    Prompt prompt = promptBuilder.build(session, profileWith(20, List.of("SOUL.md")));

    List<Message> messages = prompt.messages();
    assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
    String systemText = messages.get(0).getText();
    assertThat(systemText).contains("You are an ops agent.").contains("soul persona");
    assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
  }

  @Test
  @DisplayName("system prompt 末尾含当前日期时间")
  void systemPromptEndsWithCurrentDateTime() {
    Session session = new Session("s-1", "ops");

    Prompt prompt = promptBuilder.build(session, profileWith(20, List.of()));

    String systemText = prompt.messages().get(0).getText();
    assertThat(systemText).contains(LocalDate.now().toString());
  }

  @Test
  @DisplayName("历史超N轮被截断")
  void historyExceedingMaxTurns_isTruncated() {
    Session session = new Session("s-1", "ops");
    for (int i = 0; i < 25; i++) {
      session.append(new UserMessage("question-" + i));
    }

    Prompt prompt = promptBuilder.build(session, profileWith(20, List.of()));

    List<Message> messages = prompt.messages();
    assertThat(messages).hasSize(1 + 20);
    assertThat(messages.get(1).getText()).isEqualTo("question-5");
    assertThat(messages.get(messages.size() - 1).getText()).isEqualTo("question-24");
  }

  @Test
  @DisplayName("长期记忆接入位_本节恒为空段")
  void memorySlot_reservedAndEmpty() {
    Session session = new Session("s-1", "ops");
    session.append(new UserMessage("hi"));

    Prompt prompt = promptBuilder.build(session, profileWith(20, List.of()));

    assertThat(prompt.messages()).noneMatch(message -> message.getText().contains("MEMORY"));
  }

  private static Profile profileWith(int maxHistoryTurns, List<String> bootstrap) {
    return new Profile(
        "ops",
        null,
        new Profile.Identity("Oryx", "You are an ops agent.", null),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        bootstrap,
        new Profile.Settings(10, maxHistoryTurns),
        null,
        null);
  }

  @Test
  @DisplayName("工具严格按Profile声明顺序选择且空声明不授权")
  void exactToolSubset() {
    OryxTool first = mock(OryxTool.class);
    OryxTool second = mock(OryxTool.class);
    PromptBuilder builder =
        new PromptBuilder(new ContextLoader(workspace), Map.of("first", first, "second", second));
    assertThat(
            builder
                .build(new Session("s", "ops"), toolProfile(List.of("second", "first")))
                .availableTools())
        .containsExactly(second, first);
    assertThat(builder.build(new Session("s", "ops"), toolProfile(List.of())).availableTools())
        .isEmpty();
  }

  @Test
  @DisplayName("未知重复工具明确失败且不污染其他Profile")
  void rejectsInvalidDeclarations() {
    OryxTool tool = mock(OryxTool.class);
    PromptBuilder builder = new PromptBuilder(new ContextLoader(workspace), Map.of("known", tool));
    for (List<String> names : List.of(List.of("missing"), List.of("known", "known"))) {
      assertThatThrownBy(() -> builder.build(new Session("s", "ops"), toolProfile(names)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(
            builder.build(new Session("s", "ops"), toolProfile(List.of("known"))).availableTools())
        .containsExactly(tool);
  }

  private static Profile toolProfile(List<String> tools) {
    return new Profile(
        "ops", null, null, null, tools, null, null, null, null, null, null, null, null, null);
  }
}
