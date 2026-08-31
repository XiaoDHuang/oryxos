package com.oryxos.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oryxos.core.memory.MemoryScope;
import org.junit.jupiter.api.Test;

abstract class AbstractMemoryStoreContractTest {

  abstract LongTermMemoryStore openStore() throws Exception;

  abstract LongTermMemoryStore unavailableStore() throws Exception;

  @Test
  void preservesFullCoreAcrossReopenAndExcludesItFromRecall() throws Exception {
    String core = "核心原文Spring\n中文🚀".repeat(1000);
    LongTermMemoryStore store = openStore();
    store.append(core, MemoryScope.CORE);
    assertThat(store.load()).contains(core).doesNotContain("## 归档记忆");
    assertThat(store.recall("Spring")).isEmpty();
    assertThat(openStore().load()).contains(core);
  }

  @Test
  void rejectsEmptyInputAndDoesNotCreateEmptySections() throws Exception {
    LongTermMemoryStore store = openStore();
    assertThat(store.load()).isEmpty();
    for (String invalid : new String[] {null, "", " \t\n"}) {
      assertThatThrownBy(() -> store.append(invalid, MemoryScope.CORE))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> store.recall(invalid)).isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(store.load()).isEmpty();
  }

  @Test
  void defaultsNullScopeAndMakesConfirmedWriteVisible() throws Exception {
    LongTermMemoryStore store = openStore();
    store.append("项目使用Spring", null);
    assertThat(store.load()).contains("## 归档记忆").doesNotContain("## 核心记忆");
    assertThat(openStore().load()).isNotBlank();
  }

  @Test
  void neverDisguisesStorageFailureAsEmptyOrSuccess() throws Exception {
    LongTermMemoryStore store = unavailableStore();
    assertThatThrownBy(store::load).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> store.recall("Spring")).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> store.append("原文", MemoryScope.CORE))
        .isInstanceOf(RuntimeException.class);
  }
}
