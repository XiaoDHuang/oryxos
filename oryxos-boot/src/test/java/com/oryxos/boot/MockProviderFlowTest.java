package com.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oryxos.channel.cli.CliChannel;
import com.oryxos.core.context.ContextLoader;
import com.oryxos.core.memory.MemoryService;
import com.oryxos.core.profile.ProfileLoader;
import com.oryxos.core.profile.ProfileRegistry;
import com.oryxos.core.react.AgentService;
import com.oryxos.core.react.ProfileContext;
import com.oryxos.core.react.PromptBuilder;
import com.oryxos.core.react.ReActLoop;
import com.oryxos.core.react.ToolExecutor;
import com.oryxos.core.react.ToolInvocationAudit;
import com.oryxos.core.session.SessionManager;
import com.oryxos.memory.LongTermMemoryStore;
import com.oryxos.provider.LlmCallAudit;
import com.oryxos.provider.MockChatModel;
import com.oryxos.provider.ProviderService;
import com.oryxos.provider.ToolSchemaAdapter;
import com.oryxos.storage.audit.LlmCallRepository;
import com.oryxos.tool.ToolRegistry;
import com.oryxos.web.api.GlobalExceptionHandler;
import com.oryxos.web.api.MemoryApiController;
import com.oryxos.web.api.SessionApiController;
import com.oryxos.web.api.ToolApiController;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.ResourceAccessException;

class MockProviderFlowTest {

  @TempDir Path root;

  @Test
  @DisplayName("无key两轮一次保存_CLI与REST及管理台查询三面同源")
  void cliAndRestShareRealStateAndExactAuditCounts() throws Exception {
    HumanFlowFixture.prepare(root, "mock", "mock-script");
    HumanFlowFixture.storageAndTools(root)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var registry = profiles();
              var service = service(context, registry, new MockChatModel());
              var sessions = context.getBean(SessionManager.class);
              var source = context.getBean(DataSource.class);
              var cliSession = sessions.getOrCreate("cli", "reader", "flow");
              StringWriter output = new StringWriter();
              new CliChannel(
                      service,
                      cliSession,
                      new BufferedReader(
                          new StringReader("记住：" + HumanFlowFixture.FACT + "\n/quit\n")),
                      new PrintWriter(output))
                  .run();
              assertThat(output.toString()).contains("已记住");
              HumanFlowFixture.assertAccounts(source, cliSession.id(), 2, "save_memory", true);
              var history = HumanFlowFixture.history(source, cliSession.id());
              assertThat(history.findValuesAsText("role"))
                  .containsExactly("user", "assistant", "tool", "assistant");
              var mvc =
                  MockMvcBuilders.standaloneSetup(
                          new SessionApiController(service, sessions, registry),
                          new MemoryApiController(
                              context.getBean(LongTermMemoryStore.class), "markdown"),
                          new ToolApiController(context.getBean(ToolRegistry.class)))
                      .setControllerAdvice(new GlobalExceptionHandler())
                      .build();
              var detail =
                  HumanFlowFixture.JSON.readTree(
                      mvc.perform(get("/api/v1/sessions/{id}", cliSession.id()))
                          .andExpect(status().isOk())
                          .andReturn()
                          .getResponse()
                          .getContentAsString());
              assertThat(detail.path("data").path("totalMessages").asInt()).isEqualTo(4);
              assertThat(detail.path("data").path("messages").get(2).path("content").asText())
                  .isEqualTo("已记住");
              assertThat(
                      mvc.perform(get("/api/v1/sessions"))
                          .andReturn()
                          .getResponse()
                          .getContentAsString())
                  .contains(cliSession.id());
              var webSession = sessions.getOrCreate("web", "reader", "flow");
              var pending =
                  mvc.perform(
                          post("/api/v1/sessions/{id}/messages", webSession.id())
                              .contentType(MediaType.APPLICATION_JSON)
                              .content(
                                  HumanFlowFixture.JSON.writeValueAsString(
                                      Map.of("content", "记住：" + HumanFlowFixture.FACT))))
                      .andReturn();
              mvc.perform(asyncDispatch(pending)).andExpect(status().isOk());
              HumanFlowFixture.assertAccounts(source, webSession.id(), 2, "save_memory", true);
              assertThat(HumanFlowFixture.history(source, webSession.id()).findValuesAsText("role"))
                  .isEqualTo(history.findValuesAsText("role"));
              assertThat(
                      mvc.perform(get("/api/v1/memory"))
                          .andReturn()
                          .getResponse()
                          .getContentAsString())
                  .contains("北京");
              assertThat(
                      HumanFlowFixture.JSON
                          .readTree(
                              mvc.perform(get("/api/v1/tools"))
                                  .andReturn()
                                  .getResponse()
                                  .getContentAsString())
                          .path("data")
                          .findValuesAsText("name"))
                  .containsExactlyInAnyOrderElementsOf(HumanFlowFixture.TOOLS);
              assertThat(Files.readString(root.resolve("memory/MEMORY.md")))
                  .contains(HumanFlowFixture.FACT);
              assertThat(ProfileContext.current()).isNull();
            });
  }

  @Test
  @DisplayName("Provider失败_Sandbox拦截_真实文件工具异常均写失败审计")
  void failuresLeaveAuditsAndNextConversationStillWorks() throws Exception {
    HumanFlowFixture.prepare(root, "mock", "mock-script");
    HumanFlowFixture.storageAndTools(root)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var registry = profiles();
              var sessions = context.getBean(SessionManager.class);
              var source = context.getBean(DataSource.class);
              final String original = Files.readString(root.resolve("memory/MEMORY.md"));
              var failed = sessions.getOrCreate("web", "provider-failure", "flow");
              var unavailable =
                  service(
                      context,
                      registry,
                      prompt -> {
                        throw new ResourceAccessException("合成连接故障");
                      });
              assertThatThrownBy(() -> unavailable.process(failed, "测试失败"))
                  .isInstanceOf(ResourceAccessException.class);
              assertThat(
                      new JdbcTemplate(source)
                          .queryForList(
                              "SELECT success FROM llm_calls WHERE session_id=?", failed.id()))
                  .hasSize(1)
                  .allSatisfy(row -> assertThat(((Number) row.get("success")).intValue()).isZero());
              for (Path path :
                  List.of(root.resolveSibling("denied-file"), root.resolve("missing-file"))) {
                var session = sessions.getOrCreate("web", path.getFileName().toString(), "flow");
                String reply =
                    service(context, registry, readFile(path)).process(session, "读取合成文件");
                assertThat(reply).contains("ERROR:");
                HumanFlowFixture.assertAccounts(source, session.id(), 2, "read_file", false);
                assertThat(ProfileContext.current()).isNull();
              }
              assertThat(Files.readString(root.resolve("memory/MEMORY.md"))).isEqualTo(original);
              assertThat(
                      service(context, registry, new MockChatModel())
                          .process(sessions.getOrCreate("web", "after-failure", "flow"), "记住：恢复正常"))
                  .contains("已记住");
            });
  }

  private ProfileRegistry profiles() {
    return new ProfileRegistry(new ProfileLoader(Set.of("mock")).loadAll(root.resolve("profiles")));
  }

  private AgentService service(
      ApplicationContext context, ProfileRegistry profiles, ChatModel model) {
    var table = context.getBean(ToolRegistry.class).asMap();
    var provider =
        new ProviderService(
            Map.of("mock", model),
            new ToolSchemaAdapter(),
            new LlmCallAudit(context.getBean(LlmCallRepository.class)));
    var prompt =
        new PromptBuilder(new ContextLoader(root), table, context.getBean(MemoryService.class));
    var executor = new ToolExecutor(table, context.getBean(ToolInvocationAudit.class));
    return new AgentService(
        new ReActLoop(provider, prompt, executor), profiles, context.getBean(SessionManager.class));
  }

  private static ChatModel readFile(Path path) throws Exception {
    String arguments = HumanFlowFixture.JSON.writeValueAsString(Map.of("path", path.toString()));
    return prompt -> {
      if (prompt.getInstructions().getLast() instanceof ToolResponseMessage) {
        return new MockChatModel().call(prompt);
      }
      var message =
          AssistantMessage.builder()
              .content("读取文件")
              .toolCalls(
                  List.of(
                      new AssistantMessage.ToolCall("read-1", "function", "read_file", arguments)))
              .build();
      return new ChatResponse(
          List.of(new Generation(message)),
          ChatResponseMetadata.builder().usage(new DefaultUsage(1, 1)).build());
    };
  }
}
