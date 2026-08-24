package com.oryxos.cli;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * 轻命令 {@code session list}:直连 SQLite 列出现有会话. 只读存储面,不起 Spring。
 *
 * @author OryxOS Contributors
 */
@Command(mixinStandardHelpOptions = true, name = "list", description = "列出现有会话")
public class SessionListCommand implements Runnable {

  @Spec private CommandSpec commandSpec;

  @Override
  public void run() {
    PrintWriter out = commandSpec.commandLine().getOut();
    if (!CliFiles.requireWorkspace(out)) {
      return;
    }
    Path db = CliFiles.workspace().resolve("oryxos.db");
    if (!Files.isRegularFile(db)) {
      out.println("(尚无量产库 —— 还没跑过任何重命令)");
      return;
    }
    String sql =
        "SELECT session_id, profile_name, channel, status, last_active_at"
            + " FROM sessions ORDER BY last_active_at DESC";
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      int count = 0;
      while (rs.next()) {
        count++;
        out.println(
            rs.getString("session_id")
                + "  "
                + rs.getString("profile_name")
                + "  "
                + rs.getString("channel")
                + "  "
                + rs.getString("status")
                + "  "
                + rs.getString("last_active_at"));
      }
      if (count == 0) {
        out.println("(无会话)");
      }
    } catch (SQLException | RuntimeException e) {
      out.println("读取会话库失败(表可能还没建 —— 先跑一次 chat/serve): " + e.getMessage());
    }
  }
}
