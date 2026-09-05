package com.oryxos.web.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oryxos.core.profile.Profile;
import com.oryxos.core.profile.ProfileRegistry;
import com.oryxos.core.react.AgentService;
import com.oryxos.core.session.Session;
import com.oryxos.core.session.SessionManager;
import com.oryxos.core.session.SessionPage;
import com.oryxos.core.session.SessionSummary;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 会话端点切片:standalone MockMvc + 真实 GlobalExceptionHandler,只验薄壳三件事(校验/包装/ 转交)与统一错误出口;真实装配由 WebSmokeIT
 * 守. Callable 端点的 200/500 走 asyncDispatch.
 *
 * @author OryxOS Contributors
 */
class SessionApiControllerTest {

  private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

  private final AgentService agentService = mock(AgentService.class);

  private final SessionManager sessionManager = mock(SessionManager.class);

  private final ProfileRegistry profileRegistry = mock(ProfileRegistry.class);

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new SessionApiController(agentService, sessionManager, profileRegistry))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("消息超过32KB_400且引擎不被调用")
  void messageOver32K_returns400WithoutTouchingEngine() throws Exception {
    String oversized = "{\"content\":\"" + "x".repeat(33 * 1024) + "\"}";

    mockMvc
        .perform(post("/api/v1/sessions/s-1/messages").contentType(JSON).content(oversized))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.message").value("消息为空或超过 32KB"));
    verify(agentService, never()).process(any(), any());
  }

  @Test
  @DisplayName("会话不存在_404")
  void unknownSession_returns404() throws Exception {
    when(sessionManager.get("ghost")).thenReturn(Optional.empty());

    mockMvc
        .perform(post("/api/v1/sessions/ghost/messages").contentType(JSON).content(body("你好")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    verify(agentService, never()).process(any(), any());
  }

  @Test
  @DisplayName("正常请求_引擎恰被调一次且回复进信封")
  void normalRequest_callsEngineExactlyOnce() throws Exception {
    Session session = new Session("web:u-1:default", "default");
    when(sessionManager.get(session.id())).thenReturn(Optional.of(session));
    when(agentService.process(session, "你好")).thenReturn("模型回复");

    MvcResult started =
        mockMvc
            .perform(
                post("/api/v1/sessions/" + session.id() + "/messages")
                    .contentType(JSON)
                    .content(body("你好")))
            .andExpect(request().asyncStarted())
            .andReturn();
    mockMvc
        .perform(asyncDispatch(started))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.reply").value("模型回复"));
    verify(agentService, times(1)).process(session, "你好");
  }

  @Test
  @DisplayName("内部异常细节_绝不能出现在500响应里")
  void internalError_neverLeaksDetails() throws Exception {
    Session session = new Session("web:u-1:default", "default");
    when(sessionManager.get(session.id())).thenReturn(Optional.of(session));
    when(agentService.process(any(), any()))
        .thenThrow(new IllegalStateException("jdbc:sqlite:/data/oryxos.db connect failed"));

    MvcResult started =
        mockMvc
            .perform(
                post("/api/v1/sessions/" + session.id() + "/messages")
                    .contentType(JSON)
                    .content(body("你好")))
            .andExpect(request().asyncStarted())
            .andReturn();
    MvcResult resolved = mockMvc.perform(asyncDispatch(started)).andReturn();
    String body = resolved.getResponse().getContentAsString();
    org.assertj.core.api.Assertions.assertThat(resolved.getResponse().getStatus()).isEqualTo(500);
    org.assertj.core.api.Assertions.assertThat(body).contains("\"errorCode\":\"INTERNAL_ERROR\"");
    org.assertj.core.api.Assertions.assertThat(body).contains("\"message\":\"服务器内部错误\"");
    org.assertj.core.api.Assertions.assertThat(body).doesNotContain("jdbc:sqlite");
  }

  @Test
  @DisplayName("已归档会话发消息_400会话已归档")
  void archivedSession_rejectsNewMessage() throws Exception {
    Session session = new Session("web:u-1:default", "default");
    session.fillArchived(true);
    when(sessionManager.get(session.id())).thenReturn(Optional.of(session));

    mockMvc
        .perform(
            post("/api/v1/sessions/" + session.id() + "/messages")
                .contentType(JSON)
                .content(body("还在吗")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.message").value("会话已归档"));
    verify(agentService, never()).process(any(), any());
  }

  @Test
  @DisplayName("创建会话缺字段_400;正常创建返回active摘要")
  void create_validatesFieldsAndReturnsSummary() throws Exception {
    mockMvc
        .perform(post("/api/v1/sessions").contentType(JSON).content("{\"profileName\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));

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
    Session created = new Session("web:u-1:default", "default");
    when(sessionManager.getOrCreate("web", "u-1", "default")).thenReturn(created);

    mockMvc
        .perform(
            post("/api/v1/sessions")
                .contentType(JSON)
                .content("{\"profileName\":\"default\",\"userId\":\"u-1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sessionId").value("web:u-1:default"))
        .andExpect(jsonPath("$.data.status").value("active"));
  }

  @Test
  @DisplayName("创建引用了未知Profile_404")
  void createWithUnknownProfile_returns404() throws Exception {
    when(profileRegistry.find("ghost")).thenReturn(Optional.empty());

    mockMvc
        .perform(
            post("/api/v1/sessions")
                .contentType(JSON)
                .content("{\"profileName\":\"ghost\",\"userId\":\"u-1\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  @DisplayName("非法分页参数_400;正常列表返回分页信封")
  void list_validatesPageArgsAndReturnsPage() throws Exception {
    when(sessionManager.listSessions(-1, 10))
        .thenThrow(new IllegalArgumentException("页码不能为负数: -1"));
    mockMvc
        .perform(get("/api/v1/sessions?page=-1&size=10"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.message").value("页码不能为负数: -1"));

    when(sessionManager.listSessions(0, 20))
        .thenReturn(
            new SessionPage(
                0,
                20,
                1,
                List.of(
                    new SessionSummary(
                        "web:u-1:default",
                        "default",
                        "web",
                        "u-1",
                        "active",
                        "2026-09-05T00:00:00Z",
                        "2026-09-05T00:01:00Z",
                        null))));
    mockMvc
        .perform(get("/api/v1/sessions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.content[0].sessionId").value("web:u-1:default"));
  }

  @Test
  @DisplayName("归档与详情_未知会话404,详情最多返回最近100条")
  void detailAndArchive_shapeAnd404() throws Exception {
    when(sessionManager.get("ghost")).thenReturn(Optional.empty());
    when(sessionManager.archive("ghost")).thenReturn(false);
    mockMvc.perform(get("/api/v1/sessions/ghost")).andExpect(status().isNotFound());
    mockMvc.perform(delete("/api/v1/sessions/ghost")).andExpect(status().isNotFound());

    Session session = new Session("web:u-1:default", "default");
    for (int index = 0; index < 120; index++) {
      session.append(new org.springframework.ai.chat.messages.UserMessage("第" + index + "条"));
    }
    when(sessionManager.get(session.id())).thenReturn(Optional.of(session));

    mockMvc
        .perform(get("/api/v1/sessions/" + session.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalMessages").value(120))
        .andExpect(jsonPath("$.data.messages.length()").value(100))
        .andExpect(jsonPath("$.data.messages[0].content").value("第20条"));
  }

  private static String body(String content) {
    return "{\"content\":\"" + content + "\"}";
  }
}
