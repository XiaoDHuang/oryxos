package com.oryxos.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * 轻命令 {@code status}:看工作区状态. 只读文件与 SQLite,不起 Spring,秒回。
 *
 * @author OryxOS Contributors
 */
@Command(mixinStandardHelpOptions = true, name = "status", description = "查看 OryxOS 工作区状态")
public class StatusCommand implements Runnable {

  @Spec private CommandSpec commandSpec;

  @Override
  public void run() {
    PrintWriter out = commandSpec.commandLine().getOut();
    Path workspace = CliFiles.workspace();
    if (!Files.isDirectory(workspace)) {
      out.println("未找到 .oryxos 工作区 —— 请先运行 oryxos init");
      return;
    }
    out.println("工作区: 已初始化");
    out.println("Profile 数: " + countProfiles(workspace));
    out.println("会话数: " + countSessions(workspace));
  }

  private static long countProfiles(Path workspace) {
    Path profiles = workspace.resolve("profiles");
    if (!Files.isDirectory(profiles)) {
      return 0;
    }
    try (Stream<Path> stream = Files.list(profiles)) {
      return stream.filter(CliFiles::isYaml).count();
    } catch (IOException | RuntimeException e) {
      return 0;
    }
  }

  /** 库文件不存在视为 0——还没跑过任何重命令. */
  static long countSessions(Path workspace) {
    Path db = workspace.resolve("oryxos.db");
    if (!Files.isRegularFile(db)) {
      return 0;
    }
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM sessions")) {
      return rs.getLong(1);
    } catch (SQLException | RuntimeException e) {
      return 0;
    }
  }
}
