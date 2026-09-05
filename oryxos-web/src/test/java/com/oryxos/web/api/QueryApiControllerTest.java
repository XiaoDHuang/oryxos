package com.oryxos.web.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oryxos.core.profile.Profile;
import com.oryxos.core.profile.ProfileRegistry;
import com.oryxos.core.tool.OryxTool;
import com.oryxos.core.tool.ToolResult;
import com.oryxos.memory.LongTermMemoryStore;
import com.oryxos.provider.ProviderProperties;
import com.oryxos.tool.ToolRegistry;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 五个只读查询端点的形状与信封断言:profiles/tools/memory/health/info 全部只读、成功信封、 字段与 contracts/api-v1.md 一致.
 *
 * @author OryxOS Contributors
 */
class QueryApiControllerTest {

  private final ProfileRegistry profileRegistry = mock(ProfileRegistry.class);

  private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

  private final LongTermMemoryStore memoryStore = mock(LongTermMemoryStore.class);

  @SuppressWarnings("unchecked")
  private final ObjectProvider<Set<String>> globalProviderNames = mock(ObjectProvider.class);

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ProviderProperties properties = new ProviderProperties();
    ProviderProperties.ProviderEntry entry = new ProviderProperties.ProviderEntry();
    entry.setName("deepseek");
    properties.setProviders(List.of(entry));
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ProfileApiController(profileRegistry),
                new ToolApiController(toolRegistry),
                new MemoryApiController(memoryStore, "markdown"),
                new SystemApiController(
                    "OryxOS", "1.0.0-SNAPSHOT", "测试", globalProviderNames, properties))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("profiles端点_返回摘要字段")
  void profiles_returnsSummaries() throws Exception {
    Profile profile =
        new Profile(
            "default",
            "默认 Agent",
            new Profile.Identity("小O", null, null),
            new Profile.Provider("deepseek", "deepseek-chat", 0.7, null),
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
    when(profileRegistry.all()).thenReturn(List.of(profile));

    mockMvc
        .perform(get("/api/v1/profiles"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data[0].name").value("default"))
        .andExpect(jsonPath("$.data[0].agentName").value("小O"))
        .andExpect(jsonPath("$.data[0].provider").value("deepseek"))
        .andExpect(jsonPath("$.data[0].model").value("deepseek-chat"));
  }

  @Test
  @DisplayName("tools端点_返回名称与描述")
  void tools_returnsNamesAndDescriptions() throws Exception {
    OryxTool tool =
        new OryxTool() {
          @Override
          public String getName() {
            return "read_file";
          }

          @Override
          public String getDescription() {
            return "读取文件";
          }

          @Override
          public String getInputSchema() {
            return "{}";
          }

          @Override
          public ToolResult execute(String argumentsJson) {
            throw new UnsupportedOperationException("只读端点永不执行工具");
          }
        };
    when(toolRegistry.all()).thenReturn(List.of(tool));

    mockMvc
        .perform(get("/api/v1/tools"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].name").value("read_file"))
        .andExpect(jsonPath("$.data[0].description").value("读取文件"));
  }

  @Test
  @DisplayName("memory端点_返回后端名与全量视图")
  void memory_returnsBackendAndFullView() throws Exception {
    when(memoryStore.load()).thenReturn("## 核心记忆\n偏好 Spring Boot");

    mockMvc
        .perform(get("/api/v1/memory"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.backend").value("markdown"))
        .andExpect(jsonPath("$.data.content").value("## 核心记忆\n偏好 Spring Boot"));
  }

  @Test
  @DisplayName("health端点_固定ok")
  void health_returnsOk() throws Exception {
    mockMvc
        .perform(get("/api/v1/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("ok"));
  }

  @Test
  @DisplayName("info端点_带版本与provider连通状态")
  void info_returnsVersionAndProviderStatus() throws Exception {
    when(globalProviderNames.getIfAvailable(ArgumentMatchers.<Supplier<Set<String>>>any()))
        .thenReturn(Set.of("deepseek"));

    mockMvc
        .perform(get("/api/v1/info"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("OryxOS"))
        .andExpect(jsonPath("$.data.version").value("1.0.0-SNAPSHOT"))
        .andExpect(jsonPath("$.data.providers[0].name").value("deepseek"))
        .andExpect(jsonPath("$.data.providers[0].status").value("registered"));
  }

  @Test
  @DisplayName("已声明但凭据缺失的provider_标unavailable")
  void info_marksSkippedProviderUnavailable() throws Exception {
    when(globalProviderNames.getIfAvailable(ArgumentMatchers.<Supplier<Set<String>>>any()))
        .thenReturn(Set.of());

    mockMvc
        .perform(get("/api/v1/info"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.providers[0].status").value("unavailable"));
  }
}
