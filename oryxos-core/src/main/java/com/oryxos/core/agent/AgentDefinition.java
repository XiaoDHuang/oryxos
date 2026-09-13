package com.oryxos.core.agent;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个 Agent 目录的解析结果(29 节):frontmatter 已做 camelCase 归一化与 ${ENV} 解析、正文已剥离围栏、资源只记位置不读内容. 渐进式披露:内容只经底座
 * read_file/shell 按需进上下文。
 *
 * @author OryxOS Contributors
 */
public record AgentDefinition(
    Path agentDir,
    Map<String, Object> frontmatter,
    String body,
    Path scriptsDir,
    Path skillsDir,
    Path referenceFile) {

  /** 防御性拷贝成不可变视图:frontmatter 是解析产出的可变 Map,不能让调用方经引用改坏定义. */
  public AgentDefinition {
    frontmatter = Collections.unmodifiableMap(new LinkedHashMap<>(frontmatter));
  }
}
