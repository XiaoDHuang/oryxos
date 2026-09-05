package com.oryxos.web.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oryxos.core.profile.Profile;
import com.oryxos.core.profile.ProfileRegistry;
import com.oryxos.core.react.AgentService;
import com.oryxos.core.session.Session;
import com.oryxos.core.session.SessionManager;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 一次性调用端点切片:每次调用都拿全新三元组会话、跑完归档;未知 Agent 404;32KB 防呆与 sessions 端点同规.
 *
 * @author OryxOS Contributors
 */
class AgentApiControllerTest {

  private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

  private final AgentService agentService = mock(AgentService.class);

  private final SessionManager sessionManager = mock(SessionManager.class);

  private final ProfileRegistry profileRegistry = mock(ProfileRegistry.class);

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new AgentApiController(agentService, sessionManager, profileRegistry))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("两次调用各自新建一次性会话_且跑完都归档")
  void invoke_freshSessionPerCallAndArchivedAfterwards() throws Exception {
    Profile profile =
        new Profile(
            "default",
            null,
            null,
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
    when(profileRegistry.find("default")).thenReturn(Optional.of(profile));
    when(sessionManager.getOrCreate(eq("invoke"), startsWith("invoke-"), eq("default")))
        .thenAnswer(
            invocation ->
                new Session("invoke:" + invocation.getArgument(1) + ":default", "default"));
    when(agentService.process(any(), any())).thenReturn("一次性回复");

    for (int index = 0; index < 2; index++) {
      MvcResult started =
          mockMvc
              .perform(
                  post("/api/v1/agents/default/invoke")
                      .contentType(JSON)
                      .content("{\"content\":\"总结这句\"}"))
              .andExpect(request().asyncStarted())
              .andReturn();
      mockMvc
          .perform(asyncDispatch(started))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.reply").value("一次性回复"));
    }

    ArgumentCaptor<String> users = ArgumentCaptor.forClass(String.class);
    verify(sessionManager, times(2)).getOrCreate(eq("invoke"), users.capture(), eq("default"));
    List<String> capturedUsers = users.getAllValues();
    org.assertj.core.api.Assertions.assertThat(capturedUsers)
        .allMatch(user -> user.startsWith("invoke-"));
    org.assertj.core.api.Assertions.assertThat(capturedUsers.get(0))
        .isNotEqualTo(capturedUsers.get(1));
    // 每轮跑完都归档,invoke 不留活跃残留
    verify(sessionManager, times(2))
        .archive(org.mockito.ArgumentMatchers.startsWith("invoke:invoke-"));
    verify(agentService, times(2)).process(any(), any());
  }

  @Test
  @DisplayName("未知Agent名_404且引擎不被调用")
  void invokeUnknownAgent_returns404() throws Exception {
    when(profileRegistry.find("ghost")).thenReturn(Optional.empty());

    mockMvc
        .perform(
            post("/api/v1/agents/ghost/invoke").contentType(JSON).content("{\"content\":\"hi\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    verify(agentService, never()).process(any(), any());
    verify(sessionManager, never()).getOrCreate(any(), any(), any());
  }

  @Test
  @DisplayName("invoke消息超过32KB_400且引擎不被调用")
  void invokeOver32K_returns400WithoutTouchingEngine() throws Exception {
    String oversized = "{\"content\":\"" + "x".repeat(33 * 1024) + "\"}";

    mockMvc
        .perform(post("/api/v1/agents/default/invoke").contentType(JSON).content(oversized))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.message").value("消息为空或超过 32KB"));
    verify(agentService, never()).process(any(), any());
  }
}
