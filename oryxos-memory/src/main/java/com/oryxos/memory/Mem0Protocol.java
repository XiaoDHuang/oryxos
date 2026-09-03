package com.oryxos.memory;

import static java.net.HttpURLConnection.HTTP_ACCEPTED;
import static java.net.HttpURLConnection.HTTP_CONFLICT;
import static java.net.HttpURLConnection.HTTP_GATEWAY_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oryxos.core.memory.MemoryScope;
import com.oryxos.memory.MemoryOperationException.Code;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 固定自有协议的验证边界，不把上游文字当作成功或错误分类. */
final class Mem0Protocol {
  private static final Logger LOG = LoggerFactory.getLogger(Mem0Protocol.class);
  private static final String RECEIVED = "RECEIVED";
  private static final String RUNNING = "RUNNING";
  private static final String FAILED = "FAILED";
  private static final String ABORTED = "ABORTED";
  private static final String COMMITTED = "COMMITTED";
  private static final String SAVE = "SAVE";
  private static final String CORE = "CORE";
  private static final String ARCHIVAL = "ARCHIVAL";
  private static final String NOOP = "NOOP";
  private static final String CHANGED = "CHANGED";
  private static final String OPERATION_DEADLINE = "OPERATION_DEADLINE";
  private static final String SCHEMA_VERSION = "schema_version";
  private static final String REVISION = "revision";
  private static final String SNAPSHOT_REVISION = "snapshot_revision";
  private static final String RETURNED_COUNT = "returned_count";
  private static final String EXPIRES_AT = "expires_at";
  private static final String NEXT_CURSOR = "next_cursor";
  private static final String STAGED_ENGINE = "staged_engine";
  private static final String ATOMIC_HISTORY = "atomic_history";
  private static final String REVISION_PAGINATION = "revision_pagination";
  private static final String MEMORY_EFFECTS_APPLIED = "memory_effects_applied";
  private static final String HISTORY_COMPLETE = "history_complete";
  private static final int MAX_RECALL_ITEMS = 20;
  private static final int MIN_TOKEN_CHARACTER = 33;
  private static final int MAX_PAGE_ITEMS = 100;
  private static final int MAX_TOKEN_CHARACTER = 126;
  private static final int MAX_ACTIONS = 128;
  private static final int HTTP_UNPROCESSABLE_ENTITY = 422;
  private static final int MAX_TOKEN_CHARACTERS = 8192;
  private static final int MAX_REQUEST_BYTES = 256 * 1024;
  private static final char NUL = '\0';

  static final String PREFIX = "/oryx-memory/v1";
  static final int MAX_RESPONSE_BYTES = 1024 * 1024;
  private static final int MAX_TEXT_BYTES = 32 * 1024;
  private static final ObjectMapper JSON =
      new ObjectMapper(
          JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
  private static final Set<String> IDENTITY =
      Set.of(
          "request_id",
          "replayed",
          "operation_id",
          "workspace_id",
          "kind",
          "scope",
          "request_hash",
          "state");

  private Mem0Protocol() {}

  record Operation(UUID id, UUID workspace, String kind, String scope, String hash, byte[] body) {}

  record Receipt(String state, long revision, Code failure, List<String> items) {
    boolean pending() {
      return RECEIVED.equals(state) || RUNNING.equals(state);
    }
  }

  record Snapshot(
      String token, long revision, long coreCount, long archivalCount, long expiresAt) {}

  record Entry(String id, String content, long created, long updated) {}

  record Page(List<Entry> items, boolean complete, String cursor) {}

  static Operation operation(UUID workspace, String kind, MemoryScope scope, String text) {
    requireInput(text);
    MemoryScope selected = scope == null ? MemoryScope.ARCHIVAL : scope;
    String scopeName = selected.name();
    ObjectNode body = JSON.createObjectNode().put("kind", kind);
    if (SAVE.equals(kind)) {
      body.put("scope", scopeName).put("content", text);
    } else {
      body.put("query", text);
    }
    String source = "oryx-memory-v1\0" + workspace + "\0" + kind + "\0" + scopeName + "\0" + text;
    try {
      String hash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(source.getBytes(StandardCharsets.UTF_8)));
      byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
      if (bytes.length > MAX_REQUEST_BYTES) {
        throw new IllegalArgumentException("记忆请求超过字节限制");
      }
      return new Operation(UUID.randomUUID(), workspace, kind, scopeName, hash, bytes);
    } catch (NoSuchAlgorithmException exception) {
      throw new MemoryOperationException(Code.MEMORY_INVALID_CONFIG);
    }
  }

  static void requireInput(String value) {
    if (value == null || value.isBlank() || value.indexOf(NUL) >= 0) {
      throw new IllegalArgumentException("记忆文本为空或包含不支持的字符");
    }
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
          throw new IllegalArgumentException("记忆文本包含无效Unicode");
        }
      } else if (Character.isLowSurrogate(current)) {
        throw new IllegalArgumentException("记忆文本包含无效Unicode");
      }
    }
    if (value.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
      throw new IllegalArgumentException("记忆文本超过字节限制");
    }
  }

  static JsonNode parse(byte[] bytes) {
    if (bytes.length == 0 || bytes.length > MAX_RESPONSE_BYTES) {
      throw invalid();
    }
    try {
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      try (JsonParser parser = JSON.createParser(text)) {
        JsonNode result = JSON.readTree(parser);
        if (result == null || !result.isObject() || parser.nextToken() != null) {
          throw invalid();
        }
        uuid(result, "request_id");
        return result;
      }
    } catch (CharacterCodingException exception) {
      throw invalid();
    } catch (IOException exception) {
      throw invalid();
    }
  }

  static void capabilities(JsonNode value) {
    fields(
        value,
        Set.of(),
        "request_id",
        "protocol",
        SCHEMA_VERSION,
        "sdk_version",
        STAGED_ENGINE,
        ATOMIC_HISTORY,
        REVISION_PAGINATION,
        "build_version",
        "limits");
    equal(value, "protocol", "oryx-memory-v1");
    equal(value, "sdk_version", "1.0.11+oryx.1");
    if (number(value, SCHEMA_VERSION) != 1
        || !bool(value, STAGED_ENGINE)
        || !bool(value, ATOMIC_HISTORY)
        || !bool(value, REVISION_PAGINATION)) {
      throw invalid();
    }
    // 构建版本与固定限制是兼容性检查的一部分：缺省或漂移都不得通过。
    if (!string(value, "build_version").matches("[0-9a-f]{64}")) {
      throw invalid();
    }
    JsonNode limits = value.get("limits");
    fields(
        limits,
        Set.of(),
        "content_max_bytes",
        "request_max_bytes",
        "response_max_bytes",
        "page_size_max",
        "recall_top",
        "operation_deadline_seconds");
    if (number(limits, "content_max_bytes") != MAX_TEXT_BYTES
        || number(limits, "request_max_bytes") != MAX_REQUEST_BYTES
        || number(limits, "response_max_bytes") != MAX_RESPONSE_BYTES
        || number(limits, "page_size_max") != MAX_PAGE_ITEMS
        || number(limits, "recall_top") != MAX_RECALL_ITEMS
        || number(limits, "operation_deadline_seconds") != 30) {
      throw invalid();
    }
  }

  static Receipt receipt(JsonNode value, Operation request, int status) {
    equal(value, "operation_id", request.id().toString());
    equal(value, "workspace_id", request.workspace().toString());
    equal(value, "kind", request.kind());
    equal(value, "scope", request.scope());
    equal(value, "request_hash", request.hash());
    bool(value, "replayed");
    String state = string(value, "state");
    if (RECEIVED.equals(state) || RUNNING.equals(state)) {
      fields(value, IDENTITY, "deadline_at");
      instant(value, "deadline_at");
      if (status != HTTP_ACCEPTED) {
        throw invalid();
      }
      return new Receipt(state, 0, null, List.of());
    }
    if (FAILED.equals(state) || ABORTED.equals(state)) {
      fields(value, IDENTITY, "completed_at", "error_code", MEMORY_EFFECTS_APPLIED);
      instant(value, "completed_at");
      if (bool(value, MEMORY_EFFECTS_APPLIED)
          || !Set.of(
                  HTTP_CONFLICT, HTTP_UNPROCESSABLE_ENTITY, HTTP_UNAVAILABLE, HTTP_GATEWAY_TIMEOUT)
              .contains(status)) {
        throw invalid();
      }
      String code = string(value, "error_code");
      boolean invalidAbort = !OPERATION_DEADLINE.equals(code) || status != HTTP_GATEWAY_TIMEOUT;
      if (ABORTED.equals(state) && invalidAbort) {
        throw invalid();
      }
      return new Receipt(state, 0, serviceCode(code), List.of());
    }
    if (!COMMITTED.equals(state) || status != HTTP_OK) {
      throw invalid();
    }
    instant(value, "committed_at");
    long revision = number(value, REVISION);
    if (SAVE.equals(request.kind())) {
      fields(
          value,
          IDENTITY,
          "committed_at",
          REVISION,
          "outcome",
          "action_counts",
          "affected_ids",
          HISTORY_COMPLETE);
      validateSave(value, request.scope(), revision);
      return new Receipt(state, revision, null, List.of());
    }
    fields(
        value,
        IDENTITY,
        "committed_at",
        REVISION,
        SNAPSHOT_REVISION,
        "items",
        RETURNED_COUNT,
        "truncated_by_bytes");
    if (revision != number(value, SNAPSHOT_REVISION)) {
      throw invalid();
    }
    List<String> items = recallItems(value);
    LOG.debug("记忆召回确认：条目数={}，按字节缩减={}", items.size(), bool(value, "truncated_by_bytes"));
    return new Receipt(state, revision, null, items);
  }

  private static void validateSave(JsonNode value, String scope, long revision) {
    if (revision < 1 || !bool(value, HISTORY_COMPLETE)) {
      throw invalid();
    }
    JsonNode counts = value.get("action_counts");
    fields(counts, Set.of(), "ADD", "UPDATE", "DELETE");
    long add = number(counts, "ADD");
    long update = number(counts, "UPDATE");
    long delete = number(counts, "DELETE");
    if (add > MAX_ACTIONS || update > MAX_ACTIONS || delete > MAX_ACTIONS) {
      throw invalid();
    }
    long total = add + update + delete;
    if (total > MAX_ACTIONS) {
      throw invalid();
    }
    JsonNode ids = array(value, "affected_ids", MAX_ACTIONS);
    Set<String> unique = new HashSet<>();
    for (JsonNode id : ids) {
      if (!unique.add(uuidText(id))) {
        throw invalid();
      }
    }
    String outcome = string(value, "outcome");
    if (NOOP.equals(outcome)) {
      if (total != 0 || !ids.isEmpty() || CORE.equals(scope)) {
        throw invalid();
      }
    } else if (!CHANGED.equals(outcome) || total == 0 || ids.isEmpty() || ids.size() > total) {
      throw invalid();
    }
    boolean invalidCoreAction = add != 1 || update != 0 || delete != 0 || ids.size() != 1;
    if (CORE.equals(scope) && invalidCoreAction) {
      throw invalid();
    }
  }

  private static List<String> recallItems(JsonNode value) {
    JsonNode items = array(value, "items", MAX_RECALL_ITEMS);
    boolean truncated = bool(value, "truncated_by_bytes");
    boolean shortPrefix = !items.isEmpty() && items.size() < MAX_RECALL_ITEMS;
    boolean invalidTruncation = truncated && !shortPrefix;
    if (number(value, RETURNED_COUNT) != items.size() || invalidTruncation) {
      throw invalid();
    }
    List<String> contents = new ArrayList<>();
    Set<String> unique = new HashSet<>();
    double lastScore = Double.POSITIVE_INFINITY;
    String lastId = "";
    for (JsonNode item : items) {
      fields(item, Set.of(), "memory_id", "version_id", "content", "scope", "score");
      final String id = uuid(item, "memory_id");
      uuid(item, "version_id");
      equal(item, "scope", ARCHIVAL);
      JsonNode scoreNode = item.get("score");
      if (scoreNode == null || !scoreNode.isNumber()) {
        throw invalid();
      }
      double score = scoreNode.doubleValue();
      boolean invalidScore = !Double.isFinite(score) || score < 0 || score > 1;
      boolean invalidTie = Double.compare(score, lastScore) == 0 && id.compareTo(lastId) <= 0;
      if (invalidScore || score > lastScore || invalidTie || !unique.add(id)) {
        throw invalid();
      }
      contents.add(content(item));
      lastScore = score;
      lastId = id;
    }
    return List.copyOf(contents);
  }

  static Snapshot snapshot(JsonNode value) {
    fields(
        value,
        Set.of(),
        "request_id",
        "snapshot_id",
        REVISION,
        EXPIRES_AT,
        "core_count",
        "archival_count");
    String token = token(value, "snapshot_id");
    long archival = number(value, "archival_count");
    if (archival > MAX_PAGE_ITEMS || number(value, EXPIRES_AT) <= Instant.now().getEpochSecond()) {
      throw invalid();
    }
    return new Snapshot(
        token,
        number(value, REVISION),
        number(value, "core_count"),
        archival,
        number(value, EXPIRES_AT));
  }

  static Page page(JsonNode value, Snapshot snapshot, String scope, long expected) {
    fields(
        value,
        Set.of(),
        "request_id",
        "snapshot_id",
        REVISION,
        "scope",
        "items",
        "total_count",
        "complete",
        NEXT_CURSOR);
    equal(value, "snapshot_id", snapshot.token());
    equal(value, "scope", scope);
    if (number(value, REVISION) != snapshot.revision()
        || number(value, "total_count") != expected) {
      throw invalid();
    }
    JsonNode items = array(value, "items", MAX_PAGE_ITEMS);
    List<Entry> entries = new ArrayList<>();
    for (JsonNode item : items) {
      fields(
          item,
          Set.of(),
          "memory_id",
          "version_id",
          "content",
          "scope",
          "created_revision",
          "updated_revision");
      equal(item, "scope", scope);
      uuid(item, "version_id");
      long created = number(item, "created_revision");
      long updated = number(item, "updated_revision");
      if (created < 1 || created > updated || updated > snapshot.revision()) {
        throw invalid();
      }
      entries.add(new Entry(uuid(item, "memory_id"), content(item), created, updated));
    }
    boolean complete = bool(value, "complete");
    String cursor = null;
    if (complete) {
      if (!value.get(NEXT_CURSOR).isNull()) {
        throw invalid();
      }
    } else {
      cursor = token(value, NEXT_CURSOR);
      if (entries.isEmpty()) {
        throw invalid();
      }
    }
    return new Page(List.copyOf(entries), complete, cursor);
  }

  private static Code serviceCode(String value) {
    return switch (value) {
      case "WRITE_CONFLICT", "REQUEST_ID_CONFLICT" -> Code.MEMORY_WRITE_CONFLICT;
      case "HISTORY_UNAVAILABLE" -> Code.MEMORY_HISTORY_FAILURE;
      case OPERATION_DEADLINE -> Code.MEMORY_TIMEOUT;
      case "ACCESS_DENIED" -> Code.MEMORY_ACCESS_DENIED;
      case "INVALID_INPUT",
          "ENGINE_INVALID_RESULT",
          "ENGINE_LIMIT_EXCEEDED",
          "RESULT_LIMIT_EXCEEDED" ->
          Code.MEMORY_PROTOCOL_ERROR;
      case "AUDIT_UNAVAILABLE", "SERVICE_FAILURE" -> Code.MEMORY_SERVICE_FAILURE;
      default -> throw invalid();
    };
  }

  private static String content(JsonNode value) {
    String content = string(value, "content");
    try {
      requireInput(content);
    } catch (IllegalArgumentException exception) {
      throw invalid();
    }
    return content;
  }

  private static String token(JsonNode value, String name) {
    String token = string(value, name);
    boolean invalidCharacters =
        token.chars().anyMatch(c -> c < MIN_TOKEN_CHARACTER || c > MAX_TOKEN_CHARACTER);
    if (token.length() > MAX_TOKEN_CHARACTERS || invalidCharacters) {
      throw invalid();
    }
    return token;
  }

  private static void fields(JsonNode value, Set<String> common, String... names) {
    if (value == null || !value.isObject()) {
      throw invalid();
    }
    Set<String> expected = new HashSet<>(common);
    expected.addAll(List.of(names));
    Set<String> actual = new HashSet<>();
    value.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expected)) {
      throw invalid();
    }
  }

  private static JsonNode array(JsonNode value, String name, int maximum) {
    JsonNode result = value.get(name);
    if (result == null || !result.isArray() || result.size() > maximum) {
      throw invalid();
    }
    return result;
  }

  private static String string(JsonNode value, String name) {
    JsonNode item = value.get(name);
    if (item == null || !item.isTextual() || item.textValue().isBlank()) {
      throw invalid();
    }
    return item.textValue();
  }

  private static void equal(JsonNode value, String name, String expected) {
    if (!expected.equals(string(value, name))) {
      throw invalid();
    }
  }

  private static long number(JsonNode value, String name) {
    JsonNode item = value.get(name);
    if (item == null
        || !item.isIntegralNumber()
        || !item.canConvertToLong()
        || item.longValue() < 0) {
      throw invalid();
    }
    return item.longValue();
  }

  private static boolean bool(JsonNode value, String name) {
    JsonNode item = value.get(name);
    if (item == null || !item.isBoolean()) {
      throw invalid();
    }
    return item.booleanValue();
  }

  private static String uuid(JsonNode value, String name) {
    return uuidText(value.get(name));
  }

  private static String uuidText(JsonNode value) {
    if (value == null || !value.isTextual()) {
      throw invalid();
    }
    try {
      String text = value.textValue();
      UUID uuid = UUID.fromString(text);
      if (!uuid.toString().equals(text) || uuid.equals(new UUID(0, 0))) {
        throw invalid();
      }
      return text;
    } catch (IllegalArgumentException exception) {
      throw invalid();
    }
  }

  private static void instant(JsonNode value, String name) {
    try {
      Instant.parse(string(value, name));
    } catch (DateTimeParseException exception) {
      throw invalid();
    }
  }

  static MemoryOperationException invalid() {
    return new MemoryOperationException(Code.MEMORY_PROTOCOL_ERROR);
  }
}
