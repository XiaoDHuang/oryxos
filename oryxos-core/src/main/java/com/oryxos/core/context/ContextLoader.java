package com.oryxos.core.context;

import com.oryxos.core.profile.Profile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 提供来自文件的 prompt 上下文:Profile 的 Bootstrap 文件(从工作区根读取)与 Skill 剧本({@code
 * .oryxos/skills/<name>/SKILL.md}). 课件的两条硬规则:每次调用都从磁盘 重新加载(不做缓存 —— 用户编辑立即生效),且绝不静默跳过 —— 引用的 Skill
 * 缺失是 错误,Bootstrap 文件缺失至少 WARN,因为静默丢掉人设是最恶劣的软失败。
 *
 * @author OryxOS Contributors
 */
public class ContextLoader {

  private static final Logger LOGGER = LoggerFactory.getLogger(ContextLoader.class);

  private final Path workspaceDir;

  /** 创建以给定工作区({@code .oryxos/} 目录)为根的加载器. */
  public ContextLoader(Path workspaceDir) {
    this.workspaceDir = workspaceDir;
  }

  /** 读取并拼接 profile 的 bootstrap 文件与 skill 剧本,每次调用都全新读取. */
  public String load(Profile profile) {
    StringBuilder context = new StringBuilder();
    for (String bootstrapFile : profile.bootstrap()) {
      Path path = workspaceDir.resolve(bootstrapFile);
      if (Files.isRegularFile(path)) {
        context.append(readFile(path)).append('\n');
      } else {
        LOGGER.warn("profile 引用的 Bootstrap 文件 {} 缺失", sanitize(bootstrapFile));
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

  private static String readFile(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      LOGGER.error("读取上下文文件 {} 失败: {}", sanitize(path), sanitize(e.getMessage()));
      return "";
    }
  }

  /** 外部来源的值进入日志行前,先剥掉 CR/LF. */
  private static String sanitize(Object value) {
    return String.valueOf(value).replace('\r', '_').replace('\n', '_');
  }
}
