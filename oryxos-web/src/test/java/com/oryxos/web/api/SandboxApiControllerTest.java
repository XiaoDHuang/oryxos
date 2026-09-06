package com.oryxos.web.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oryxos.tool.sandbox.WhitelistSandbox;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 白名单管理端点切片:三类增删查的契约(参数校验、404/400 语义、返回最新生效视图). 运行期生效语义由 WhitelistSandboxTest 在 tool 模块直接守。
 *
 * @author OryxOS Contributors
 */
class SandboxApiControllerTest {

  private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

  private final WhitelistSandbox sandbox = mock(WhitelistSandbox.class);

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new SandboxApiController(sandbox))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private void stubView() {
    when(sandbox.allowedPathView()).thenReturn(List.of(".oryxos"));
    when(sandbox.allowedCommandView()).thenReturn(Set.of("ls", "cat"));
    when(sandbox.allowedDomainView()).thenReturn(Set.of("api.openweathermap.org"));
  }

  @Test
  @DisplayName("查询_返回三类生效名单")
  void view_returnsThreeLists() throws Exception {
    stubView();

    mockMvc
        .perform(get("/api/v1/sandbox/whitelist"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.allowedPaths[0]").value(".oryxos"))
        .andExpect(jsonPath("$.data.allowedCommands[0]").exists())
        .andExpect(jsonPath("$.data.allowedDomains[0]").value("api.openweathermap.org"));
  }

  @Test
  @DisplayName("新增条目_调用对应类别allow并返回最新视图;重复新增幂等200")
  void add_delegatesPerTypeAndReturnsFreshView() throws Exception {
    stubView();
    when(sandbox.allowCommand("python")).thenReturn(true);

    mockMvc
        .perform(
            post("/api/v1/sandbox/whitelist/entries")
                .contentType(JSON)
                .content("{\"type\":\"shell\",\"value\":\"python\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.allowedCommands").isArray());
    verify(sandbox).allowCommand("python");

    when(sandbox.allowCommand("python")).thenReturn(false);
    mockMvc
        .perform(
            post("/api/v1/sandbox/whitelist/entries")
                .contentType(JSON)
                .content("{\"type\":\"shell\",\"value\":\"python\"}"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("非法type或空value_400且不碰沙箱")
  void addOrRemove_invalidInput_returns400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/sandbox/whitelist/entries")
                .contentType(JSON)
                .content("{\"type\":\"db\",\"value\":\"x\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    mockMvc
        .perform(
            post("/api/v1/sandbox/whitelist/entries")
                .contentType(JSON)
                .content("{\"type\":\"shell\",\"value\":\" \"}"))
        .andExpect(status().isBadRequest());
    verify(sandbox, never()).allowCommand(ArgumentMatchers.any());
  }

  @Test
  @DisplayName("沙箱拒绝的新增项_原样转成400")
  void add_sandboxRejects_returns400() throws Exception {
    when(sandbox.allowCommand("rm -rf")).thenThrow(new IllegalArgumentException("命令项须为单个首 token"));

    mockMvc
        .perform(
            post("/api/v1/sandbox/whitelist/entries")
                .contentType(JSON)
                .content("{\"type\":\"shell\",\"value\":\"rm -rf\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("命令项须为单个首 token"));
  }

  @Test
  @DisplayName("删除_命中返回最新视图,未命中404")
  void remove_hitReturnsView_missReturns404() throws Exception {
    stubView();
    when(sandbox.denyDomain("api.openweathermap.org")).thenReturn(true);

    mockMvc
        .perform(
            delete("/api/v1/sandbox/whitelist/entries")
                .param("type", "http")
                .param("value", "api.openweathermap.org"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.allowedDomains").isArray());

    when(sandbox.denyDomain("absent.example.com")).thenReturn(false);
    mockMvc
        .perform(
            delete("/api/v1/sandbox/whitelist/entries")
                .param("type", "http")
                .param("value", "absent.example.com"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
  }
}
