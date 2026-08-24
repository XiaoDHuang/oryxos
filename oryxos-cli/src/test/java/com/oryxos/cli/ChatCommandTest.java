package com.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.oryxos.core.profile.ProfileRegistry;
import com.oryxos.core.session.SessionManager;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatCommandTest {

  @Test
  @DisplayName("未知Profile_不创建孤儿会话")
  void unknownProfile_doesNotCreateSession() {
    SessionManager sessionManager = mock(SessionManager.class);
    StringWriter output = new StringWriter();

    var session =
        ChatCommand.resolveSession(
            new ProfileRegistry(List.of()),
            sessionManager,
            "missing",
            "wang",
            new PrintWriter(output, true));

    assertThat(session).isEmpty();
    assertThat(output.toString()).contains("Profile 未注册: missing");
    verifyNoInteractions(sessionManager);
  }
}
