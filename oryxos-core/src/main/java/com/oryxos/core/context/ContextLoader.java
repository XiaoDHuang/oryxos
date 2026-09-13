package com.oryxos.core.context;

import com.oryxos.core.profile.Profile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 提供来自文件的 prompt 上下文:Profile 的 Bootstrap 文件(从工作区根读取)与 Skill 剧本({@code
 * .oryxos/skills/<name>/SKILL.md}),以及 29 节的 Agent 正文({@code identity.promptFile},从目录主文件现读、去
 * frontmatter). 课件的两条硬规则:每次调用都从磁盘 重新加载(不做缓存 —— 用户编辑立即生效),且绝不静默跳过 —— 引用的 Skill 缺失是错误,显式 Bootstrap
 * 引用也必须失败,因为缺失人格仍继续执行会改变 Agent 行为。
 *
 * @author OryxOS Contributors
 */
public class ContextLoader {

  private static final Logger LOGGER = LoggerFactory.getLogger(ContextLoader.class);

  private static final String FENCE = "---";

  private final Path workspaceDir;

  /** 创建以给定工作区({@code .oryxos/} 目录)为根的加载器. */
  public ContextLoader(Path workspaceDir) {
    this.workspaceDir = workspaceDir;
  }

  /** 读取并拼接 profile 的 prompt 文件正文、bootstrap 文件与 skill 剧本,每次调用都全新读取. */
  public String load(Profile profile) {
    StringBuilder context = new StringBuilder();
    if (profile.identity() != null && profile.identity().promptFile() != null) {
      context.append(loadPromptFile(profile.identity().promptFile())).append('\n');
    }
    for (String bootstrapFile : profile.bootstrap()) {
      Path path = workspaceDir.resolve(bootstrapFile);
      if (Files.isRegularFile(path)) {
        context.append(readFile(path)).append('\n');
      } else {
        LOGGER.warn("profile 引用的 Bootstrap 文件 {} 缺失", sanitize(bootstrapFile));
        throw new IllegalStateException("Profile 引用的 Bootstrap 不存在: " + sanitize(bootstrapFile));
      }
    }
    for (String skill : profile.skills()) {
      Path path = workspaceDir.resolve("skills").resolve(skill).resolve("SKILL.md");
      if (!Files.isRegularFile(path)) {
        throw new IllegalStateException("profile 引用的 Skill 不存在: " + sanitize(skill));
      }
      context.append(readFile(path)).append('\n');
    }
    return context.toString().strip();
  }

  /**
   * 现读 prompt 文件(29 节:Agent 正文经此注入). 缺失按"显式引用必须失败"的同级原则抛错;内容以 {@code ---} 行开头且有 闭合围栏时剥掉 frontmatter
   * 只留正文,无围栏的普通 prompt 文件原样返回。
   */
  private String loadPromptFile(String promptFile) {
    Path path = workspaceDir.resolve(promptFile);
    if (!Files.isRegularFile(path)) {
      LOGGER.warn("profile 引用的 prompt 文件 {} 缺失", sanitize(promptFile));
      throw new IllegalStateException("Profile 引用的 prompt 文件不存在: " + sanitize(promptFile));
    }
    return stripFrontmatter(readFile(path));
  }

  /** 去 frontmatter:首个 {@code ---} 行与闭合 {@code ---} 行之间的段落剥离;无围栏则原样返回(空操作保证兼容). */
  private static String stripFrontmatter(String text) {
    String[] lines = text.split("\r?\n", -1);
    if (lines.length == 0 || !FENCE.equals(lines[0].strip())) {
      return text;
    }
    for (int i = 1; i < lines.length; i++) {
      if (FENCE.equals(lines[i].strip())) {
        return String.join("\n", Arrays.copyOfRange(lines, i + 1, lines.length)).strip();
      }
    }
    return text;
  }

  private static String readFile(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      LOGGER.error("读取上下文文件 {} 失败: {}", sanitize(path), sanitize(e.getMessage()));
      throw new IllegalStateException("上下文文件读取失败: " + sanitize(path), e);
    }
  }

  /** 外部来源的值进入日志行前,先剥掉 CR/LF. */
  private static String sanitize(Object value) {
    return String.valueOf(value).replace('\r', '_').replace('\n', '_');
  }
}
