package com.oryxos.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oryxos.memory.MemoryOperationException.Code;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MemoryOperationExceptionTest {

  private static final UUID OPERATION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final Map<Code, String> LABELS =
      Map.of(
          Code.MEMORY_INVALID_CONFIG, "记忆配置无效",
          Code.MEMORY_ACCESS_DENIED, "记忆访问被拒绝",
          Code.MEMORY_PROTOCOL_ERROR, "记忆服务响应无效",
          Code.MEMORY_SERVICE_FAILURE, "记忆服务暂不可用",
          Code.MEMORY_TIMEOUT, "记忆操作超时",
          Code.MEMORY_OUTCOME_UNKNOWN, "记忆保存结果不确定，请勿重复保存",
          Code.MEMORY_WRITE_CONFLICT, "记忆写入冲突",
          Code.MEMORY_HISTORY_FAILURE, "记忆历史保全失败");

  @Test
  void typeAndCodesAreClosedAndConstructorsAcceptNoArbitraryMessage() {
    assertThat(Modifier.isFinal(MemoryOperationException.class.getModifiers())).isTrue();
    assertThat(Set.of(Code.values())).containsExactlyInAnyOrderElementsOf(LABELS.keySet());
    Set<String> constructors =
        Arrays.stream(MemoryOperationException.class.getConstructors())
            .map(
                constructor ->
                    Arrays.stream(constructor.getParameterTypes())
                        .map(Class::getSimpleName)
                        .collect(Collectors.joining(",")))
            .collect(Collectors.toSet());
    assertThat(constructors).containsExactlyInAnyOrder("Code", "Code,UUID");
  }

  @Test
  void everyCodeUsesFixedSafeMessageAndOptionalCanonicalOperationId() {
    for (Map.Entry<Code, String> entry : LABELS.entrySet()) {
      if (entry.getKey() == Code.MEMORY_OUTCOME_UNKNOWN) {
        continue;
      }
      MemoryOperationException withoutId = new MemoryOperationException(entry.getKey());
      assertThat(withoutId.code()).isEqualTo(entry.getKey());
      assertThat(withoutId.operationId()).isEmpty();
      assertThat(withoutId.safeMessage())
          .isEqualTo(entry.getKey().name() + "：" + entry.getValue())
          .doesNotContain("null");

      MemoryOperationException withId = new MemoryOperationException(entry.getKey(), OPERATION_ID);
      assertThat(withId.operationId()).contains(OPERATION_ID);
      assertThat(withId.safeMessage())
          .isEqualTo(
              entry.getKey().name() + "：" + entry.getValue() + "；operationId=" + OPERATION_ID);
    }
  }

  @Test
  void unknownOutcomeAlwaysRequiresIdAndCannotAttachRemoteCause() {
    assertThatThrownBy(() -> new MemoryOperationException(Code.MEMORY_OUTCOME_UNKNOWN))
        .isInstanceOf(IllegalArgumentException.class);
    MemoryOperationException exception =
        new MemoryOperationException(Code.MEMORY_OUTCOME_UNKNOWN, OPERATION_ID);
    assertThat(exception.safeMessage())
        .isEqualTo("MEMORY_OUTCOME_UNKNOWN：记忆保存结果不确定，请勿重复保存；operationId=" + OPERATION_ID);
    assertThatThrownBy(
            () -> exception.initCause(new IllegalStateException("remote-secret-response")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(exception.getCause()).isNull();
    assertThat(exception.safeMessage()).doesNotContain("remote-secret-response");
  }

  @Test
  void nullCodeIsRejectedWithoutReflectingOperationInput() {
    assertThatThrownBy(() -> new MemoryOperationException(null, OPERATION_ID))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("记忆错误分类不能为空");
  }
}
