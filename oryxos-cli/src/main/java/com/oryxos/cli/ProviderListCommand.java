package com.oryxos.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.yaml.snakeyaml.Yaml;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * 轻命令 {@code provider list}:扫 profiles 目录的 YAML,汇总各 Profile 引用到的 provider 名 (配置面视角),不起 Spring.
 * 运行时已注册的 provider 清单由重命令/Web 端点(26 节)提供。
 *
 * @author OryxOS Contributors
 */
@Command(name = "list", description = "列出各 Profile 引用到的 LLM provider")
public class ProviderListCommand implements Runnable {

  @Spec private CommandSpec commandSpec;

  @Override
  public void run() {
    PrintWriter out = commandSpec.commandLine().getOut();
    if (!CliFiles.requireWorkspace(out)) {
      return;
    }
    Path profiles = CliFiles.workspace().resolve("profiles");
    if (!Files.isDirectory(profiles)) {
      out.println("(无 Profile)");
      return;
    }
    Map<String, java.util.Set<String>> providerToProfiles = new TreeMap<>();
    Yaml yaml = new Yaml();
    try (Stream<Path> stream = Files.list(profiles)) {
      for (Path file : stream.filter(CliFiles::isYaml).sorted().toList()) {
        try {
          Map<String, Object> map = yaml.load(Files.readString(file));
          Object provider = map == null ? null : map.get("provider");
          if (provider instanceof Map<?, ?> providerMap && providerMap.get("name") != null) {
            String providerName = String.valueOf(providerMap.get("name"));
            Path fileName = file.getFileName();
            if (fileName == null) {
              continue;
            }
            String profileName = fileName.toString().replaceFirst("\\.(yaml|yml)$", "");
            providerToProfiles
                .computeIfAbsent(providerName, key -> new TreeSet<>())
                .add(profileName);
          }
        } catch (IOException | RuntimeException e) {
          out.println("跳过无法解析的 Profile 文件: " + file.getFileName());
        }
      }
    } catch (IOException | RuntimeException e) {
      out.println("读取 profiles 目录失败: " + e.getMessage());
      return;
    }
    if (providerToProfiles.isEmpty()) {
      out.println("(无 provider 引用)");
      return;
    }
    providerToProfiles.forEach(
        (name, usedBy) -> out.println(name + "  <-  " + String.join(", ", usedBy)));
  }
}
