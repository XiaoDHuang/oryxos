package com.oryxos.channel.cli;

import com.oryxos.core.react.AgentService;
import com.oryxos.core.session.Session;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * {@code oryxos chat} 的交互壳:读一行 → 交给引擎 → 打印回复,直到 /quit. CLI 只进出消息, 不碰 Agent 逻辑(不拼
 * prompt、不调模型、不执行工具)——思考和执行全在引擎里。IO 走注入的 Reader/Writer 而非裸 System.in/out,既不打日志通道,也方便测试。
 *
 * @author OryxOS Contributors
 */
public class CliChannel {

  private final AgentService agentService;

  private final Session session;

  private final BufferedReader in;

  private final PrintWriter out;

  /** 创建绑定某会话的交互通道. */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "通道的本职就是持有并驱动这些协作对象(会话与 IO 流),拷贝它们反而语义错误。")
  public CliChannel(
      AgentService agentService, Session session, BufferedReader in, PrintWriter out) {
    this.agentService = agentService;
    this.session = session;
    this.in = in;
    this.out = out;
  }

  /** 进入读-转交-打印循环,直到用户输入 /quit 或输入流关闭. */
  public void run() throws IOException {
    out.println("已进入对话(Profile: " + session.profileName() + "),输入 /quit 退出");
    while (true) {
      out.print("> ");
      out.flush();
      String line = in.readLine();
      if (line == null || "/quit".equals(line.trim())) {
        break;
      }
      if (line.isBlank()) {
        continue;
      }
      String reply = agentService.process(session, line);
      out.println(reply);
    }
    out.println("已退出对话");
  }
}
