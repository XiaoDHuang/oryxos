package com.oryxos.core.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.oryxos.core.profile.Profile;
import com.oryxos.core.profile.ProfileRegistry;
import com.oryxos.core.session.Session;
import com.oryxos.core.session.SessionManager;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentServiceTest {

  private final ReActLoop reActLoop = mock(ReActLoop.class);
  private final SessionManager sessionManager = mock(SessionManager.class);
  private final Profile profile = profileNamed("ops");
  private final ProfileRegistry profileRegistry = new ProfileRegistry(List.of(profile));
  private final AgentService agentService =
      new AgentService(reActLoop, profileRegistry, sessionManager);
  private final Session session = new Session("s-1", "ops");

  @AfterEach
  void tearDown() {
    ProfileContext.clear();
  }

  @Test
  @DisplayName("处理期间_ProfileContext可取到当前Profile")
  void profileContextAvailableDuringExecution() {
    when(reActLoop.run(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              assertThat(ProfileContext.current()).isSameAs(profile);
              return "reply";
            });

    String reply = agentService.process(session, "hi");

    assertThat(reply).isEqualTo("reply");
  }

  @Test
  @DisplayName("处理中抛异常_ProfileContext也必须被清掉")
  void processThrows_profileContextStillCleared() {
    when(reActLoop.run(any(), any(), any())).thenThrow(new RuntimeException("boom"));

    assertThatThrownBy(() -> agentService.process(session, "hi"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("boom");

    assertThat(ProfileContext.current()).isNull();
  }

  @Test
  @DisplayName("处理结束后_Session被持久化")
  void afterProcess_sessionIsSaved() {
    when(reActLoop.run(any(), any(), any())).thenReturn("reply");

    agentService.process(session, "hi");

    verify(sessionManager).save(session);
  }

  @Test
  @DisplayName("Profile不存在_报错且不进入循环")
  void unknownProfile_failsFast() {
    Session orphan = new Session("s-2", "no-such-profile");

    assertThatThrownBy(() -> agentService.process(orphan, "hi"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no-such-profile");
  }

  private static Profile profileNamed(String name) {
    return new Profile(
        name, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }
}
