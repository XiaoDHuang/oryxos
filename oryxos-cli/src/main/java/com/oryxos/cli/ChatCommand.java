package com.oryxos.cli;

import com.oryxos.channel.cli.CliChannel;
import com.oryxos.core.profile.ProfileRegistry;
import com.oryxos.core.react.AgentService;
import com.oryxos.core.session.Session;
import com.oryxos.core.session.SessionManager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * 重命令 {@code chat}:起 Spring 运行时,按三元组(channel=cli、本机账号、Profile 名)取会话, 然后把交互交给 {@link CliChannel}. 会话
 * id 的拼接只在 SessionManager 内部,这里只提供三元组。
 *
 * @author OryxOS Contributors
 */
@Command(
    mixinStandardHelpOptions = true,
    name = "chat",
    description = "在终端里和 Agent 交互式对话(/quit 退出)")
public class ChatCommand implements Runnable {

  @Option(names = "--profile", defaultValue = "default", description = "使用的 Profile 名")
  String profileName;

  @Spec private CommandSpec commandSpec;

  @Override
  public void run() {
    try (ConfigurableApplicationContext context = SpringRuntime.start(false)) {
      AgentService agentService = context.getBean(AgentService.class);
      SessionManager sessionManager = context.getBean(SessionManager.class);
      PrintWriter out = commandSpec.commandLine().getOut();
      Optional<Session> session =
          resolveSession(
              context.getBean(ProfileRegistry.class),
              sessionManager,
              profileName,
              System.getProperty("user.name"),
              out);
      if (session.isEmpty()) {
        return;
      }
      BufferedReader in =
          new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
      new CliChannel(agentService, session.orElseThrow(), in, out).run();
    } catch (IOException e) {
      throw new IllegalStateException("读取终端输入失败", e);
    }
  }

  /** 先验 Profile 再建会话,避免未知 Profile 留下永远不会使用的孤儿记录. */
  static Optional<Session> resolveSession(
      ProfileRegistry registry,
      SessionManager sessionManager,
      String profileName,
      String userName,
      PrintWriter out) {
    if (registry.find(profileName).isEmpty()) {
      out.println("Profile 未注册: " + profileName + "(可用 oryxos profile list 查看)");
      return Optional.empty();
    }
    return Optional.of(sessionManager.getOrCreate("cli", userName, profileName));
  }
}
