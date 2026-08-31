package com.oryxos.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.oryxos.core.memory.MemoryScope;
import com.oryxos.core.session.Session;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

@DisplayName("统一Memory端口实现")
class MemoryServiceImplTest {

  @TempDir Path directory;

  @Test
  void storeConstructorPreservesDelegationRolesAndFailures() {
    LongTermMemoryStore store = mock(LongTermMemoryStore.class);
    when(store.load()).thenReturn("## 核心记忆\n原文");
    when(store.recall("语义查询")).thenReturn(List.of("匹配"));
    MemoryServiceImpl service = new MemoryServiceImpl(store);
    service.remember("归档", null);
    verify(store).append("归档", MemoryScope.ARCHIVAL);
    assertThat(service.recall("语义查询")).containsExactly("匹配");
    Session session = new Session("s", "p");
    session.append(new UserMessage("旧消息"));
    session.append(new AssistantMessage("新消息"));
    assertThat(service.buildContext(session, 1))
        .satisfiesExactly(
            message -> assertThat(message).isInstanceOf(SystemMessage.class),
            message -> assertThat(message).isInstanceOf(AssistantMessage.class));
    when(store.load()).thenThrow(new IllegalStateException("读取失败"));
    assertThatThrownBy(() -> service.buildContext(session, 1)).hasMessage("读取失败");
  }

  @Test
  @DisplayName("remember和recall只委托长期记忆且null scope缺省归档")
  void delegatesRememberAndRecall() {
    LongTermMemory memory = mock(LongTermMemory.class);
    when(memory.recallByKeyword("Java")).thenReturn(List.of("命中"));
    MemoryServiceImpl service = new MemoryServiceImpl(memory);

    service.remember("偏好", null);

    verify(memory).append("偏好", MemoryScope.ARCHIVAL);
    assertThat(service.recall("Java")).containsExactly("命中");
    verify(memory).recallByKeyword("Java");
  }

  @Test
  @DisplayName("空内容空关键词与负历史上限明确失败")
  void rejectsInvalidInputs() {
    MemoryServiceImpl service = service();

    assertThatThrownBy(() -> service.remember(" ", MemoryScope.CORE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.recall(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.buildContext(new Session("s", "p"), -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("长期记忆独立SystemMessage位于最近历史之前")
  void buildsMemoryMessageBeforeRecentHistory() {
    LongTermMemory memory = mock(LongTermMemory.class);
    when(memory.load()).thenReturn("## 核心记忆\n关键偏好");
    MemoryServiceImpl service = new MemoryServiceImpl(memory);
    Session session = new Session("channel:user:profile", "profile");
    session.append(new UserMessage("第一条"));
    session.append(new AssistantMessage("第二条"));
    session.append(new UserMessage("第三条"));

    var context = service.buildContext(session, 2);

    assertThat(context).hasSize(3);
    assertThat(context.get(0)).isInstanceOf(SystemMessage.class);
    assertThat(context.get(0).getText()).contains("关键偏好");
    assertThat(context.get(1)).isInstanceOf(AssistantMessage.class);
    assertThat(context.get(1).getText()).isEqualTo("第二条");
    assertThat(context.get(2)).isInstanceOf(UserMessage.class);
    assertThat(context.get(2).getText()).isEqualTo("第三条");
  }

  @Test
  @DisplayName("空长期记忆不生成空SystemMessage且N为零无历史")
  void omitsEmptyMemoryAndZeroHistory() {
    LongTermMemory memory = mock(LongTermMemory.class);
    when(memory.load()).thenReturn("");
    MemoryServiceImpl service = new MemoryServiceImpl(memory);
    Session session = new Session("s", "p");
    session.append(new UserMessage("不应返回"));

    assertThat(service.buildContext(session, 0)).isEmpty();
  }

  @Test
  @DisplayName("每次buildContext重新读取长期记忆")
  void reloadsLongTermMemoryForEveryContextBuild() throws Exception {
    Path workspace = directory.resolve(".oryxos");
    Files.createDirectories(workspace);
    LongTermMemory first = new LongTermMemory(workspace);
    MemoryServiceImpl service = new MemoryServiceImpl(first);
    assertThat(service.buildContext(new Session("s1", "p"), 20)).isEmpty();

    new LongTermMemory(workspace).append("外部新写入", MemoryScope.CORE);

    assertThat(service.buildContext(new Session("s2", "p"), 20))
        .singleElement()
        .satisfies(message -> assertThat(message.getText()).contains("外部新写入"));
  }

  private MemoryServiceImpl service() {
    Path workspace = directory.resolve(".oryxos");
    try {
      Files.createDirectories(workspace);
    } catch (java.io.IOException exception) {
      throw new IllegalStateException(exception);
    }
    return new MemoryServiceImpl(new LongTermMemory(workspace));
  }
}
