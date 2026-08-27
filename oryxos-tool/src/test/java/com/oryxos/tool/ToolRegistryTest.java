package com.oryxos.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.oryxos.core.profile.Profile;
import com.oryxos.core.react.ProfileContext;
import com.oryxos.core.react.ToolExecutor;
import com.oryxos.core.react.ToolInvocationAudit;
import com.oryxos.core.tool.OryxTool;
import com.oryxos.core.tool.ToolResult;
import com.oryxos.tool.builtin.FileTools;
import com.oryxos.tool.sandbox.SandboxViolationException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

@DisplayName("统一工具注册表")
class ToolRegistryTest {

  @Test
  @DisplayName("拒绝空对象空名称空描述和非法Schema")
  void validatesMetadata() {
    ToolRegistry registry = new ToolRegistry();
    for (OryxTool tool :
        Arrays.asList(
            null,
            stub("", "有效", "{}"),
            stub("a", " ", "{}"),
            stub("a", "有效", "[]"),
            stub("a", "有效", "{\"$ref\":\"https://invalid/schema\"}"))) {
      assertThrows(IllegalArgumentException.class, () -> registry.register(tool));
    }
    assertTrue(registry.all().isEmpty());
  }

  @Test
  @DisplayName("单个和批内重名都拒绝且已有实例不覆盖")
  void rejectsDuplicateNames() {
    ToolRegistry registry = new ToolRegistry();
    OryxTool first = stub("first", "描述", "{}");
    registry.register(first);
    assertThrows(
        IllegalArgumentException.class, () -> registry.register(stub("first", "其他", "{}")));
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.registerAll(List.of(stub("new", "描述", "{}"), stub("new", "描述", "{}"))));
    assertSame(first, registry.asMap().get("first"));
    assertFalse(registry.contains("new"));
  }

  @Test
  @DisplayName("批量注册失败完全不提交")
  void registersAtomically() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(stub("old", "描述", "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.registerAll(List.of(stub("good", "描述", "{}"), stub("bad", "描述", "[]"))));
    assertEquals(List.of("old"), registry.all().stream().map(OryxTool::getName).toList());
    registry.registerAll(List.of(stub("good", "描述", "{}"), stub("other", "描述", "{}")));
    assertEquals(3, registry.all().size());
  }

  @Test
  @DisplayName("快照不可变且冻结后不能继续注册")
  void snapshotsAndFreeze() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(stub("a", "描述", "{}"));
    var snapshot = registry.asMap();
    var list = registry.all();
    assertThrows(UnsupportedOperationException.class, snapshot::clear);
    assertThrows(UnsupportedOperationException.class, list::clear);
    registry.register(stub("b", "描述", "{}"));
    assertEquals(1, snapshot.size());
    assertEquals(1, list.size());
    registry.freeze();
    assertThrows(IllegalStateException.class, () -> registry.register(stub("c", "描述", "{}")));
  }

  @Test
  @DisplayName("Profile按声明顺序精确过滤且坏配置不影响其他Profile")
  void filtersExactly() {
    ToolRegistry registry = new ToolRegistry();
    registry.registerAll(List.of(stub("a", "描述", "{}"), stub("b", "描述", "{}")));
    assertTrue(registry.forProfile(profile(List.of())).isEmpty());
    assertThrows(
        IllegalArgumentException.class, () -> registry.forProfile(profile(List.of("missing"))));
    assertThrows(
        IllegalArgumentException.class, () -> registry.forProfile(profile(List.of("a", "a"))));
    assertEquals(
        List.of("b", "a"),
        registry.forProfile(profile(List.of("b", "a"))).stream().map(OryxTool::getName).toList());
  }

  @Test
  @DisplayName("Java注解注册仍默认拒绝执行")
  void discoversWithoutGrantingPermission() {
    ToolRegistry registry = new ToolRegistry();
    registry.registerAnnotated(new OryxToolContractTest.ContractPlugin());
    assertTrue(registry.contains("contractEcho"));
    assertThrows(
        SandboxViolationException.class,
        () -> registry.asMap().get("contractEcho").execute("{\"value\":\"x\"}"));
  }

  static OryxTool stub(String name, String description, String schema) {
    return new OryxTool() {
      public String getName() {
        return name;
      }

      public String getDescription() {
        return description;
      }

      public String getInputSchema() {
        return schema;
      }

      public ToolResult execute(String argumentsJson) {
        return ToolResult.ok(name, "");
      }
    };
  }

  @Test
  @DisplayName("本节恰好注册七个实际内置工具不伪造延期能力")
  void registersExactSevenBuiltins() {
    ToolRegistry registry = new ToolRegistry();
    OryxToolContractTest.registerBuiltins(registry);
    List<String> names =
        List.of("read_file", "write_file", "list_dir", "shell", "http_get", "http_post", "notify");
    assertEquals(7, registry.all().size());
    assertEquals(
        names, registry.forProfile(profile(names)).stream().map(OryxTool::getName).toList());
    assertThrows(
        IllegalArgumentException.class, () -> registry.forProfile(profile(List.of("save_memory"))));
    assertFalse(registry.contains("edit_file"));
  }

  @Test
  @DisplayName("实际内置安全拒绝经唯一执行器记为一次失败审计")
  void auditsBuiltinDenial() {
    ToolRegistry registry = new ToolRegistry();
    registry.registerAnnotated(
        new FileTools(
            action -> {
              throw new SandboxViolationException("拒绝文件操作");
            }),
        () -> {});
    ToolInvocationAudit audit = mock(ToolInvocationAudit.class);
    ProfileContext.set(profile(List.of("read_file")));
    try {
      var result =
          new ToolExecutor(registry.asMap(), audit)
              .execute(
                  "s",
                  new AssistantMessage.ToolCall(
                      "c", "function", "read_file", "{\"path\":\"unavailable\"}"));
      assertFalse(result.success());
      verify(audit)
          .record(
              eq("s"),
              eq("test"),
              eq("read_file"),
              anyString(),
              eq(false),
              isNull(),
              anyString(),
              anyLong());
      verifyNoMoreInteractions(audit);
    } finally {
      ProfileContext.clear();
    }
  }

  static Profile profile(List<String> tools) {
    return new Profile(
        "test", null, null, null, tools, null, null, null, null, null, null, null, null, null);
  }
}
