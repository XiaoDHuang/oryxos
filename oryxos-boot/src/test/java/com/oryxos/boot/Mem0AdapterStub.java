package com.oryxos.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oryxos.memory.HttpsFixture;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * 适配器侧 oryx-memory-v1 协议的内存替身：真实HTTPS与严格响应形状，供 boot 集成测试复用真实 Java Store 链路。
 * 语义只做协议保真（原文追加/包含匹配召回），不冒充真实提炼；真实模型效果由适配器侧黄金集证明.
 */
final class Mem0AdapterStub implements AutoCloseable {

  private static final String PREFIX = "/oryx-memory/v1";
  private static final String BUILD_VERSION = "a".repeat(64);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpsFixture https = HttpsFixture.open();
  private final String token = newToken();

  private static String newToken() {
    byte[] bytes = new byte[32];
    new java.security.SecureRandom().nextBytes(bytes);
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private final UUID workspace = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private final Map<UUID, ObjectNode> operations = new LinkedHashMap<>();
  private final Map<UUID, String> operationHashes = new LinkedHashMap<>();
  private final List<Entry> entries = new ArrayList<>();
  private final Map<String, List<Entry>> snapshots = new LinkedHashMap<>();
  private final Map<String, long[]> snapshotMeta = new LinkedHashMap<>();
  private long revision;
  // T065 故障脚本钩子：仅测试显式设置时才生效。
  volatile String nextFailureCode;
  volatile int nextFailureStatus;
  volatile boolean loseNextResponse;
  // 丢弃请求且完全不记录（客户端重查只会得到404，结果保持未知）。
  volatile boolean dropNextRequest;
  // 提交完成后延迟回应（客户端读超时后只能凭原ID查终态）。
  volatile long delayNextResponseMillis;
  private final java.util.concurrent.atomic.AtomicInteger requests =
      new java.util.concurrent.atomic.AtomicInteger();

  /** 记录到达替身的HTTP请求数，用于证明未选后端零网络访问. */
  int requests() {
    return requests.get();
  }

  /** 远端持久操作数（含失败终态），重放/重执行会改变它. */
  int operationCount() {
    return operations.size();
  }

  /** 当前有效条目正文，用于核对真实业务效果. */
  java.util.List<String> currentContents() {
    return entries.stream().map(Entry::content).toList();
  }

  private record Entry(
      UUID memoryId, UUID versionId, String content, String scope, long created, long updated) {}

  static Mem0AdapterStub open() {
    return new Mem0AdapterStub();
  }

  private Mem0AdapterStub() {
    https.server().setDispatcher(new StubDispatcher());
  }

  URI baseUri() {
    return https.uri("/");
  }

  HttpsFixture fixture() {
    return https;
  }

  String apiToken() {
    return token;
  }

  String workspaceId() {
    return workspace.toString();
  }

  @Override
  public void close() {
    https.close();
  }

  private static String hash(String workspace, String kind, String scope, String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(
                      ("oryx-memory-v1\0" + workspace + "\0" + kind + "\0" + scope + "\0" + text)
                          .getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private ObjectNode envelope(UUID operation, String kind, String scope, String requestHash) {
    ObjectNode node = JSON.createObjectNode();
    node.put("request_id", UUID.randomUUID().toString());
    node.put("replayed", false);
    node.put("operation_id", operation.toString());
    node.put("workspace_id", workspace.toString());
    node.put("kind", kind);
    node.put("scope", scope);
    node.put("request_hash", requestHash);
    return node;
  }

  private ObjectNode commitSave(UUID operation, String scope, String content, String requestHash) {
    ObjectNode receipt = envelope(operation, "SAVE", scope, requestHash);
    boolean duplicate =
        entries.stream().anyMatch(item -> item.scope.equals(scope) && item.content.equals(content));
    if (duplicate && !"CORE".equals(scope)) {
      receipt.put("state", "COMMITTED");
      receipt.put("committed_at", Instant.now().toString());
      receipt.put("revision", revision);
      receipt.put("outcome", "NOOP");
      receipt.putObject("action_counts").put("ADD", 0).put("UPDATE", 0).put("DELETE", 0);
      receipt.putArray("affected_ids");
      receipt.put("history_complete", true);
    } else {
      revision += 1;
      Entry entry =
          new Entry(UUID.randomUUID(), UUID.randomUUID(), content, scope, revision, revision);
      entries.add(entry);
      receipt.put("state", "COMMITTED");
      receipt.put("committed_at", Instant.now().toString());
      receipt.put("revision", revision);
      receipt.put("outcome", "CHANGED");
      receipt.putObject("action_counts").put("ADD", 1).put("UPDATE", 0).put("DELETE", 0);
      receipt.putArray("affected_ids").add(entry.memoryId().toString());
      receipt.put("history_complete", true);
    }
    return receipt;
  }

  private ObjectNode commitRecall(UUID operation, String query, String requestHash) {
    List<Entry> matches = new ArrayList<>();
    String needle = query.toLowerCase(Locale.ROOT);
    for (Entry entry : entries) {
      if ("ARCHIVAL".equals(entry.scope())
          && entry.content().toLowerCase(Locale.ROOT).contains(needle)) {
        matches.add(entry);
      }
    }
    matches.sort((left, right) -> left.memoryId().compareTo(right.memoryId()));
    ObjectNode receipt = envelope(operation, "RECALL", "ARCHIVAL", requestHash);
    receipt.put("state", "COMMITTED");
    receipt.put("committed_at", Instant.now().toString());
    receipt.put("revision", revision);
    receipt.put("snapshot_revision", revision);
    ArrayNode items = receipt.putArray("items");
    for (Entry entry : matches.stream().limit(20).toList()) {
      ObjectNode item = items.addObject();
      item.put("memory_id", entry.memoryId().toString());
      item.put("version_id", entry.versionId().toString());
      item.put("content", entry.content());
      item.put("scope", "ARCHIVAL");
      item.put("score", 1.0);
    }
    receipt.put("returned_count", matches.size() > 20 ? 20 : matches.size());
    receipt.put("truncated_by_bytes", false);
    return receipt;
  }

  private final class StubDispatcher extends Dispatcher {
    @Override
    public MockResponse dispatch(RecordedRequest request) {
      requests.incrementAndGet();
      try {
        return route(request);
      } catch (Exception exception) {
        return json(500, JSON.createObjectNode().put("error_code", "STUB_ERROR"));
      }
    }

    private MockResponse route(RecordedRequest request) {
      String authorization = request.getHeader("Authorization");
      if (!("Bearer " + token).equals(authorization)) {
        return json(401, JSON.createObjectNode().put("error_code", "UNAUTHORIZED"));
      }
      String path = request.getPath() == null ? "" : request.getPath();
      String route = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
      String[] segments = route.split("/");
      if (path.equals(PREFIX + "/capabilities")) {
        ObjectNode body =
            JSON.createObjectNode()
                .put("protocol", "oryx-memory-v1")
                .put("schema_version", 1)
                .put("sdk_version", "1.0.11+oryx.1")
                .put("staged_engine", true)
                .put("atomic_history", true)
                .put("revision_pagination", true)
                .put("build_version", BUILD_VERSION)
                .put("request_id", UUID.randomUUID().toString());
        body.set(
            "limits",
            JSON.createObjectNode()
                .put("content_max_bytes", 32768)
                .put("request_max_bytes", 262144)
                .put("response_max_bytes", 1048576)
                .put("page_size_max", 100)
                .put("recall_top", 20)
                .put("operation_deadline_seconds", 30));
        return json(200, body);
      }
      // 段序：["", "oryx-memory", "v1", "workspaces", {w}, ...]
      if (segments.length >= 5
          && "workspaces".equals(segments[3])
          && !workspace.toString().equals(segments[4])) {
        return json(403, JSON.createObjectNode().put("error_code", "FORBIDDEN"));
      }
      if (segments.length == 7 && "operations".equals(segments[5])) {
        return operation(request, UUID.fromString(segments[6]));
      }
      if (segments.length == 6
          && "snapshots".equals(segments[5])
          && "POST".equals(request.getMethod())) {
        return snapshot();
      }
      if (segments.length == 8
          && "snapshots".equals(segments[5])
          && "entries".equals(segments[7])) {
        return page(segments[6], request);
      }
      return json(404, JSON.createObjectNode().put("error_code", "NOT_FOUND"));
    }

    private MockResponse operation(RecordedRequest request, UUID id) {
      if (dropNextRequest) {
        dropNextRequest = false;
        return new MockResponse()
            .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START);
      }
      if ("GET".equals(request.getMethod())) {
        ObjectNode stored = operations.get(id);
        return stored == null
            ? json(404, JSON.createObjectNode().put("error_code", "NOT_FOUND"))
            : json(200, stored);
      }
      JsonNode body;
      try {
        body = JSON.readTree(request.getBody().readString(StandardCharsets.UTF_8));
      } catch (Exception exception) {
        return json(400, JSON.createObjectNode().put("error_code", "INVALID_INPUT"));
      }
      String kind = body.path("kind").asText("");
      String scope = "RECALL".equals(kind) ? "ARCHIVAL" : body.path("scope").asText("");
      String text =
          "RECALL".equals(kind)
              ? body.path("query").asText(null)
              : body.path("content").asText(null);
      if (text == null || (!"SAVE".equals(kind) && !"RECALL".equals(kind))) {
        return json(400, JSON.createObjectNode().put("error_code", "INVALID_INPUT"));
      }
      String requestHash = hash(workspace.toString(), kind, scope, text);
      ObjectNode existing = operations.get(id);
      if (existing != null) {
        if (!requestHash.equals(operationHashes.get(id))) {
          return json(409, JSON.createObjectNode().put("error_code", "REQUEST_ID_CONFLICT"));
        }
        ObjectNode replay = existing.deepCopy();
        replay.put("replayed", true);
        replay.put("request_id", UUID.randomUUID().toString());
        return json(200, replay);
      }
      if (nextFailureCode != null) {
        final String code = nextFailureCode;
        final int status = nextFailureStatus;
        nextFailureCode = null;
        ObjectNode failure = envelope(id, kind, scope, requestHash);
        failure.put("state", "FAILED");
        failure.put("completed_at", Instant.now().toString());
        failure.put("error_code", code);
        failure.put("memory_effects_applied", false);
        operations.put(id, failure);
        operationHashes.put(id, requestHash);
        return json(status, failure);
      }
      ObjectNode receipt =
          "SAVE".equals(kind)
              ? commitSave(id, scope, text, requestHash)
              : commitRecall(id, text, requestHash);
      operations.put(id, receipt);
      operationHashes.put(id, requestHash);
      if (loseNextResponse) {
        loseNextResponse = false;
        return new MockResponse()
            .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START);
      }
      if (delayNextResponseMillis > 0) {
        long delay = delayNextResponseMillis;
        delayNextResponseMillis = 0;
        try {
          Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      return json(200, receipt);
    }

    private MockResponse snapshot() {
      String token = "stub-snap-" + UUID.randomUUID();
      List<Entry> view = List.copyOf(entries);
      snapshots.put(token, view);
      snapshotMeta.put(
          token,
          new long[] {
            revision,
            view.stream().filter(item -> "CORE".equals(item.scope())).count(),
            Math.min(100, view.stream().filter(item -> "ARCHIVAL".equals(item.scope())).count())
          });
      ObjectNode body = JSON.createObjectNode();
      body.put("request_id", UUID.randomUUID().toString());
      body.put("snapshot_id", token);
      body.put("revision", revision);
      body.put("expires_at", Instant.now().getEpochSecond() + 300);
      body.put("core_count", snapshotMeta.get(token)[1]);
      body.put("archival_count", snapshotMeta.get(token)[2]);
      return json(200, body);
    }

    private MockResponse page(String token, RecordedRequest request) {
      List<Entry> view = snapshots.get(token);
      if (view == null) {
        return json(410, JSON.createObjectNode().put("error_code", "SNAPSHOT_EXPIRED"));
      }
      String query =
          request.getPath().contains("?")
              ? request.getPath().substring(request.getPath().indexOf('?') + 1)
              : "";
      Map<String, String> params = new LinkedHashMap<>();
      for (String pair : query.split("&")) {
        String[] parts = pair.split("=", 2);
        if (parts.length == 2) {
          params.put(parts[0], parts[1]);
        }
      }
      String scope = params.get("scope");
      int size = Integer.parseInt(params.getOrDefault("page_size", "100"));
      int offset =
          params.containsKey("cursor") ? Integer.parseInt(params.get("cursor").substring(2)) : 0;
      List<Entry> scoped =
          new ArrayList<>(view.stream().filter(item -> item.scope().equals(scope)).toList());
      // 归档只暴露最近100条窗口，与快照的 archival_count 保持一致
      if ("ARCHIVAL".equals(scope) && scoped.size() > 100) {
        scoped = new ArrayList<>(scoped.subList(scoped.size() - 100, scoped.size()));
      }
      int end = Math.min(scoped.size(), offset + size);
      final List<Entry> pageItems = scoped.subList(offset, end);
      final boolean complete = end >= scoped.size();
      ObjectNode body = JSON.createObjectNode();
      body.put("request_id", UUID.randomUUID().toString());
      body.put("snapshot_id", token);
      body.put("revision", snapshotMeta.get(token)[0]);
      body.put("scope", scope);
      ArrayNode items = body.putArray("items");
      for (Entry entry : pageItems) {
        ObjectNode item = items.addObject();
        item.put("memory_id", entry.memoryId().toString());
        item.put("version_id", entry.versionId().toString());
        item.put("content", entry.content());
        item.put("scope", entry.scope());
        item.put("created_revision", entry.created());
        item.put("updated_revision", entry.updated());
      }
      body.put("total_count", scoped.size());
      body.put("complete", complete);
      if (complete) {
        body.putNull("next_cursor");
      } else {
        body.put("next_cursor", "c-" + end);
      }
      return json(200, body);
    }

    private MockResponse json(int status, ObjectNode body) {
      return new MockResponse()
          .setResponseCode(status)
          .setHeader("Content-Type", "application/json")
          .setBody(body.toString());
    }
  }
}
