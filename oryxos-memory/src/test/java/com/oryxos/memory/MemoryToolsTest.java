package com.oryxos.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.oryxos.core.memory.MemoryScope;
import com.oryxos.core.memory.MemoryService;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

@DisplayName("Agent记忆工具")
class MemoryToolsTest {

  @Test
  @DisplayName("两个工具名称与可选scope契约固定")
  void exposesStableToolMetadata() throws Exception {
    Method save = MemoryTools.class.getMethod("saveMemory", String.class, String.class);
    Method recall = MemoryTools.class.getMethod("recallMemory", String.class);

    assertThat(save.getAnnotation(Tool.class).name()).isEqualTo("save_memory");
    assertThat(recall.getAnnotation(Tool.class).name()).isEqualTo("recall_memory");
    assertThat(save.getReturnType()).isEqualTo(String.class);
    assertThat(recall.getReturnType()).isEqualTo(String.class);
    assertThat(
            MemoryService.class
                .getMethod("remember", String.class, MemoryScope.class)
                .getReturnType())
        .isEqualTo(void.class);
    ToolParam scope = save.getParameters()[1].getAnnotation(ToolParam.class);
    assertThat(scope.required()).isFalse();
  }

  @Test
  @DisplayName("scope缺省或空白写归档")
  void defaultsMissingScopeToArchival() {
    MemoryService service = mock(MemoryService.class);
    MemoryTools tools = new MemoryTools(service);

    assertThat(tools.saveMemory("第一条", null)).isEqualTo("已记住");
    assertThat(tools.saveMemory("第二条", "  ")).isEqualTo("已记住");

    verify(service).remember("第一条", MemoryScope.ARCHIVAL);
    verify(service).remember("第二条", MemoryScope.ARCHIVAL);
  }

  @Test
  @DisplayName("显式scope忽略大小写和两侧空格")
  void parsesExplicitScope() {
    MemoryService service = mock(MemoryService.class);

    String result = new MemoryTools(service).saveMemory("关键偏好", " CoRe ");

    assertThat(result).isEqualTo("已记住");
    verify(service).remember("关键偏好", MemoryScope.CORE);
  }

  @Test
  @DisplayName("非法scope失败且不调用服务")
  void rejectsInvalidScopeBeforeCallingService() {
    MemoryService service = mock(MemoryService.class);

    assertThatThrownBy(() -> new MemoryTools(service).saveMemory("内容", "semantic"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("core")
        .hasMessageContaining("archival")
        .hasMessageNotContaining("valueOf");
    verifyNoInteractions(service);
  }

  @Test
  @DisplayName("回忆命中按换行拼接")
  void joinsRecallHitsWithNewLines() {
    MemoryService service = mock(MemoryService.class);
    when(service.recall("Java")).thenReturn(List.of("第一条", "第二条"));

    assertThat(new MemoryTools(service).recallMemory("Java")).isEqualTo("第一条\n第二条");
  }

  @Test
  @DisplayName("回忆未命中返回固定中文结果")
  void returnsStableMessageWhenRecallMisses() {
    MemoryService service = mock(MemoryService.class);
    when(service.recall("不存在")).thenReturn(List.of());

    assertThat(new MemoryTools(service).recallMemory("不存在")).isEqualTo("没有找到相关记忆");
  }

  @Test
  void mem0ReplyDoesNotPromiseThatNoopCreatedNewFacts() {
    MemoryService service = mock(MemoryService.class);
    assertThat(new MemoryTools(service, true).saveMemory("重复事实", null)).isEqualTo("记忆处理完成");
    verify(service).remember("重复事实", MemoryScope.ARCHIVAL);
  }

  @Test
  void processedReplyCannotTurnUnknownOutcomeIntoSuccess() {
    MemoryService service = mock(MemoryService.class);
    MemoryOperationException error =
        new MemoryOperationException(
            MemoryOperationException.Code.MEMORY_OUTCOME_UNKNOWN, java.util.UUID.randomUUID());
    org.mockito.Mockito.doThrow(error).when(service).remember("不确定", MemoryScope.ARCHIVAL);
    assertThatThrownBy(() -> new MemoryTools(service, true).saveMemory("不确定", null))
        .isSameAs(error);
  }
}
