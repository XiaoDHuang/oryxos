package com.oryxos.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地JSON-RPC探针不调用企业服务，stdout仅输出协议数据.
 *
 * @author OryxOS Contributors
 */
public final class McpStdioFixture {
  private McpStdioFixture() {}

  /** 仅为进程级协议验收提供本地响应，不连接真实业务系统. */
  public static void main(String[] arguments) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    String mode = arguments.length == 0 ? "normal" : arguments[0];
    if (arguments.length > 1) {
      Files.writeString(Path.of(arguments[1]), Long.toString(ProcessHandle.current().pid()));
    }
    int pages = 0;
    int calls = 0;
    try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var output = new PrintWriter(System.out, true, StandardCharsets.UTF_8)) {
      String line;
      while ((line = input.readLine()) != null) {
        JsonNode request = mapper.readTree(line);
        if (!request.hasNonNull("id")) {
          continue;
        }
        String method = request.path("method").asText();
        Object result;
        if ("initialize".equals(method)) {
          result =
              Map.of(
                  "protocolVersion",
                  request.path("params").path("protocolVersion").asText(),
                  "capabilities",
                  Map.of("tools", Map.of("listChanged", false)),
                  "serverInfo",
                  Map.of("name", "fixture", "version", "1.0"));
        } else if ("tools/list".equals(method)) {
          if ("slow".equals(mode)) {
            Thread.sleep(4000);
          }
          var page =
              mapper.readTree(
                  SchemaPreservingMcpJsonMapperTest.pageJson(
                      SchemaPreservingMcpJsonMapperTest.FULL_SCHEMA));
          String name = "paged".equals(mode) && pages > 0 ? "business_second" : "business_lookup";
          if ("cycle".equals(mode)) {
            name = "cycle_tool" + pages;
          }
          ((com.fasterxml.jackson.databind.node.ObjectNode) page.path("tools").get(0))
              .put("name", name);
          if ("cycle".equals(mode) || ("paged".equals(mode) && pages == 0)) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) page).put("nextCursor", "next");
          }
          if ("overflow".equals(mode)) {
            List<Object> tools = new ArrayList<>();
            for (int index = 0; index < 1001; index++) {
              tools.add(
                  Map.of(
                      "name",
                      "many" + index,
                      "description",
                      "数量边界",
                      "inputSchema",
                      Map.of("type", "object")));
            }
            page = mapper.valueToTree(Map.of("tools", tools));
          }
          pages++;
          result = page;
        } else if ("tools/call".equals(method)) {
          Map<String, Object> payload = new LinkedHashMap<>();
          payload.put("value", request.path("params").path("arguments").path("value").asText());
          payload.put("env", System.getenv("ORYXOS_FIXTURE_VALUE"));
          payload.put("leaked", System.getenv("ORYXOS_HOST_SENTINEL"));
          payload.put("pid", ProcessHandle.current().pid());
          payload.put("calls", ++calls);
          result =
              Map.of(
                  "content",
                  List.of(Map.of("type", "text", "text", mapper.writeValueAsString(payload))),
                  "isError",
                  false);
        } else {
          result = Map.of();
        }
        output.println(
            mapper.writeValueAsString(
                Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result)));
      }
    }
  }
}
