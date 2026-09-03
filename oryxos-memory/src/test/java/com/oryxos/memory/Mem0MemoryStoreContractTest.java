package com.oryxos.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oryxos.core.memory.MemoryScope;
import com.oryxos.memory.MemoryOperationException.Code;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Stream;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Mem0MemoryStoreContractTest extends AbstractMemoryStoreContractTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String WORKSPACE = "11111111-1111-4111-8111-111111111111";
  // 合成token仅用于本机临时CA端点，不对应任何部署凭证。
  private static final String TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
  private static final String PREFIX = "/oryx-memory/v1";
  private static final String ROOT = PREFIX + "/workspaces/" + WORKSPACE;
  private HttpsFixture https;
  private ProtocolServer remote;
  private List<URI> guarded;
  private MemoryOutboundGuard guard;

  @BeforeAll
  void startTls() {
    https = HttpsFixture.open();
  }

  @AfterAll
  void stopTls() {
    https.close();
  }

  @BeforeEach
  void resetProtocol() {
    remote = new ProtocolServer();
    https.server().setDispatcher(remote);
    guarded = new CopyOnWriteArrayList<>();
    guard =
        target -> {
          assertThat(target.getScheme()).isEqualTo("https");
          assertThat(target.getAuthority()).isEqualTo(https.uri("/").getAuthority());
          assertThat(target.getPath()).startsWith(PREFIX + "/");
          guarded.add(target);
        };
  }

  @Override
  LongTermMemoryStore openStore() {
    return new Mem0MemoryStore(properties(Duration.ofSeconds(2)), guard, https.clientSslContext());
  }

  @Override
  LongTermMemoryStore unavailableStore() {
    LongTermMemoryStore store = openStore();
    remote.reject = true;
    return store;
  }

  @Test
  void authenticatesCapabilitiesAndEveryRequestThroughGuard() {
    LongTermMemoryStore store = openStore();
    assertThat(store.load()).isEmpty();
    assertThat(remote.requests).isNotEmpty();
    assertThat(remote.requests.getFirst().getPath()).isEqualTo(PREFIX + "/capabilities");
    assertThat(guarded).hasSameSizeAs(remote.requests);
    assertThat(remote.requests)
        .allSatisfy(
            request -> assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + TOKEN));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "protocol",
        "schema_version",
        "sdk_version",
        "staged_engine",
        "atomic_history",
        "revision_pagination",
        "build_version",
        "limits",
        "request_id"
      })
  void rejectsIncompatibleOrIncompleteCapabilities(String field) {
    remote.caps =
        body -> {
          body.remove(field);
          return new MockResponse()
              .setHeader("Content-Type", "application/json")
              .setBody(body.toString());
        };
    failure(this::openStore, Code.MEMORY_PROTOCOL_ERROR);
    assertThat(remote.requests).hasSize(1);
  }

  @Test
  void requiresGuardAndDoesNotTrustUnknownCertificateAuthority() {
    failure(
        () -> new Mem0MemoryStore(properties(Duration.ofSeconds(1)), null),
        Code.MEMORY_INVALID_CONFIG);
    assertThat(remote.requests).isEmpty();
    assertThatThrownBy(() -> new Mem0MemoryStore(properties(Duration.ofSeconds(1)), guard))
        .isInstanceOf(MemoryOperationException.class);
    assertThat(remote.requests).isEmpty();
  }

  @Test
  void preservesExactUnicodeAndUsesTheFrozenCrossLanguageHash() {
    String original = "  原文é\n\"\\😀  ";
    LongTermMemoryStore store = openStore();
    store.append(original, MemoryScope.CORE);
    assertThat(store.load()).contains(original);
    assertThat(remote.results.values())
        .singleElement()
        .satisfies(
            receipt ->
                assertThat(receipt.path("request_hash").textValue())
                    .isEqualTo("990909d3e879324f6edf9395b31d6cb78620666e6cd45940159daddaf5454b9d"));
    assertThat(remote.rawInputs).containsExactly(original);
  }

  @ParameterizedTest
  @MethodSource("unrepresentableInputs")
  void rejectsUnrepresentableInputBeforeDispatch(String value) {
    LongTermMemoryStore store = openStore();
    int before = remote.requests.size();
    assertThatThrownBy(() -> store.append(value, MemoryScope.CORE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.recall(value)).isInstanceOf(IllegalArgumentException.class);
    assertThat(remote.requests).hasSize(before);
  }

  private static Stream<String> unrepresentableInputs() {
    return Stream.of("空\0值", String.valueOf((char) 0xD800), String.valueOf((char) 0xDC00));
  }

  @Test
  void enforcesUtf8InputLimitWithoutChangingLocalBackends() {
    LongTermMemoryStore store = openStore();
    store.append("a".repeat(32768), MemoryScope.CORE);
    int before = remote.requests.size();
    assertThatThrownBy(() -> store.append("a".repeat(32769), MemoryScope.CORE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.recall("中".repeat(10923)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(remote.requests).hasSize(before);
  }

  @Test
  void usesNewOperationIdsForTwoIntentionalEqualSavesAndAcceptsNoop() {
    remote.noop = true;
    LongTermMemoryStore store = openStore();
    store.append("相同事实", MemoryScope.ARCHIVAL);
    store.append("相同事实", MemoryScope.ARCHIVAL);
    assertThat(remote.results).hasSize(2);
    assertThat(remote.rawInputs).containsExactly("相同事实", "相同事实");
    assertThat(store.load()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "malformed",
        "oversized",
        "hash",
        "workspace",
        "operation",
        "scope",
        "kind",
        "history",
        "missing_history",
        "missing_receipt",
        "server",
        "denied",
        "disconnect"
      })
  void resolvesUntrustedSaveResponseByOriginalIdWithoutRepeatingPut(String fault) {
    remote.put = body -> broken(body, fault);
    LongTermMemoryStore store = openStore();
    store.append("响应丢失但已提交", MemoryScope.CORE);
    assertOnePutAndSameIdLookup();
    assertThat(store.load()).contains("响应丢失但已提交");
  }

  @ParameterizedTest
  @ValueSource(strings = {"denied", "server", "malformed", "hash", "missing_history", "not_found"})
  void reportsUnknownWhenNoTrustedSaveOutcomeCanBeObtained(String lookupFault) {
    remote.put = body -> broken(body, "server");
    remote.get = body -> broken(body, lookupFault);
    LongTermMemoryStore store = openStore();
    MemoryOperationException error =
        failure(() -> store.append("不能盲目重放", MemoryScope.CORE), Code.MEMORY_OUTCOME_UNKNOWN);
    assertThat(error.operationId())
        .contains(UUID.fromString(remote.results.keySet().iterator().next()));
    assertOnePutAndSameIdLookup();
    assertThat(error.safeMessage()).doesNotContain(TOKEN, "远端敏感诊断");
  }

  @Test
  void distinguishesPreDispatchGuardDenialFromLookupDenial() {
    AtomicBoolean deny = new AtomicBoolean();
    MemoryOutboundGuard conditional =
        target -> {
          guard.check(target);
          if (deny.get()) {
            throw new SecurityException("远端敏感诊断");
          }
        };
    LongTermMemoryStore store =
        new Mem0MemoryStore(
            properties(Duration.ofSeconds(1)), conditional, https.clientSslContext());
    deny.set(true);
    failure(() -> store.append("未派发", MemoryScope.CORE), Code.MEMORY_ACCESS_DENIED);
    assertThat(remote.results).isEmpty();
    deny.set(false);
    remote.put =
        body -> {
          deny.set(true);
          return broken(body, "server");
        };
    failure(() -> store.append("已派发", MemoryScope.CORE), Code.MEMORY_OUTCOME_UNKNOWN);
    assertThat(remote.results).hasSize(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"WRITE_CONFLICT", "HISTORY_UNAVAILABLE", "OPERATION_DEADLINE"})
  void acceptsOnlyMatchingPersistentFailureWithoutMemoryEffects(String code) {
    remote.put =
        body -> {
          ObjectNode failed = identity(body).put("replayed", false);
          failed
              .put("state", "FAILED")
              .put("error_code", code)
              .put("completed_at", Instant.now().toString())
              .put("memory_effects_applied", false);
          return json(failed).setResponseCode(422);
        };
    Code expected =
        switch (code) {
          case "WRITE_CONFLICT" -> Code.MEMORY_WRITE_CONFLICT;
          case "HISTORY_UNAVAILABLE" -> Code.MEMORY_HISTORY_FAILURE;
          default -> Code.MEMORY_TIMEOUT;
        };
    LongTermMemoryStore store = openStore();
    failure(() -> store.append("确定终态", MemoryScope.CORE), expected);
    assertThat(
            remote.requests.stream()
                .filter(r -> "GET".equals(r.getMethod()) && r.getPath().contains("/operations/")))
        .isEmpty();
  }

  @Test
  void refusesContradictoryFailedReceiptAndQueriesOriginalSave() {
    remote.put =
        body -> {
          ObjectNode failed = identity(body).put("replayed", false);
          failed
              .put("state", "FAILED")
              .put("error_code", "WRITE_CONFLICT")
              .put("completed_at", Instant.now().toString())
              .put("memory_effects_applied", true);
          return json(failed).setResponseCode(422);
        };
    LongTermMemoryStore store = openStore();
    store.append("失败信封不能证明回滚", MemoryScope.CORE);
    assertOnePutAndSameIdLookup();
  }

  @Test
  void readsAllCorePagesAndArchivalWindowFromOneSnapshot() {
    for (int index = 0; index < 103; index++) {
      remote.seed("CORE", "核心-" + index);
      remote.seed("ARCHIVAL", "归档-" + index);
    }
    remote.pageSize = 17;
    String loaded = openStore().load();
    for (int index = 0; index < 103; index++) {
      assertThat(loaded).contains("核心-" + index + "\n");
    }
    assertThat(loaded).doesNotContain("归档-0\n", "归档-1\n", "归档-2\n");
    assertThat(loaded).contains("归档-3\n", "归档-102");
    assertThat(remote.snapshots).hasSize(1);
    assertThat(remote.requestScopes).contains("CORE", "ARCHIVAL");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "scope",
        "snapshot",
        "revision",
        "total",
        "duplicate_id",
        "cursor",
        "empty_page",
        "premature_complete",
        "oversized",
        "expired"
      })
  void neverReturnsPartialCoreWhenAnyPageIsInvalid(String fault) {
    remote.seed("CORE", "不能丢失的完整核心");
    remote.seed("CORE", "第二项");
    remote.pageSize = 1;
    remote.page =
        body -> {
          switch (fault) {
            case "scope" -> body.put("scope", "ARCHIVAL");
            case "snapshot" -> body.put("snapshot_id", "different");
            case "revision" -> body.put("revision", 999);
            case "total" -> body.put("total_count", 999);
            case "duplicate_id" -> ((ArrayNode) body.get("items")).add(body.get("items").get(0));
            case "cursor" -> body.put("complete", false).put("next_cursor", "CORE:0");
            case "empty_page" -> body.set("items", JSON.createArrayNode());
            case "premature_complete" -> {
              body.put("complete", true);
              body.putNull("next_cursor");
            }
            default -> {
              return broken(body, fault);
            }
          }
          return json(body);
        };
    failure(() -> openStore().load(), Code.MEMORY_PROTOCOL_ERROR);
  }

  @ParameterizedTest
  @ValueSource(strings = {"scope", "count", "empty_truncated", "score", "order", "duplicate_id"})
  void rejectsInvalidRecallRatherThanReturningFakeOrPartialMatches(String fault) {
    remote.seed("ARCHIVAL", "先命中");
    remote.seed("ARCHIVAL", "后命中");
    remote.put =
        body -> {
          ArrayNode items = (ArrayNode) body.get("items");
          switch (fault) {
            case "scope" -> ((ObjectNode) items.get(0)).put("scope", "CORE");
            case "count" -> body.put("returned_count", 19);
            case "empty_truncated" -> {
              items.removeAll();
              body.put("returned_count", 0).put("truncated_by_bytes", true);
            }
            case "score" -> ((ObjectNode) items.get(0)).put("score", -0.5);
            case "order" -> ((ObjectNode) items.get(1)).put("score", 0.99);
            case "duplicate_id" ->
                ((ObjectNode) items.get(1))
                    .put("memory_id", items.get(0).path("memory_id").asText());
            default -> throw new AssertionError("测试故障名称无效");
          }
          return json(body);
        };
    LongTermMemoryStore store = openStore();
    failure(() -> store.recall("语义查询"), Code.MEMORY_PROTOCOL_ERROR);
    assertThat(remote.requests.stream().filter(r -> r.getPath().contains("/operations/")))
        .hasSize(1);
  }

  @Test
  void acceptsByteLimitedCompleteRecallPrefixWithoutTruncatingContent() {
    String content = "\u0001".repeat(32768);
    for (int index = 0; index < 20; index++) {
      remote.seed("ARCHIVAL", content + "");
    }
    remote.put =
        body -> {
          ArrayNode items = (ArrayNode) body.get("items");
          while (items.size() > 5) {
            items.remove(items.size() - 1);
          }
          body.put("returned_count", 5).put("truncated_by_bytes", true);
          return json(body);
        };
    List<String> result = openStore().recall("转义预算");
    assertThat(result).hasSize(5).allSatisfy(value -> assertThat(value).isEqualTo(content));
  }

  @Test
  void rejectsRedirectWithoutContactingItsTarget() {
    remote.caps =
        body ->
            new MockResponse()
                .setResponseCode(302)
                .setHeader("Location", https.uri("/unapproved").toString());
    failure(this::openStore, Code.MEMORY_PROTOCOL_ERROR);
    assertThat(remote.requests).hasSize(1);
  }

  @Test
  void boundsSlowResponseByReadBudget() {
    LongTermMemoryStore store =
        new Mem0MemoryStore(properties(Duration.ofMillis(150)), guard, https.clientSslContext());
    remote.snapshot = body -> json(body).throttleBody(1, 100, TimeUnit.MILLISECONDS);
    long started = System.nanoTime();
    failure(store::load, Code.MEMORY_TIMEOUT);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
  }

  @Test
  void pollsPendingReceiptWithoutRepeatingPutOrBusyLooping() {
    remote.put = body -> json(pending(body));
    List<Long> times = new CopyOnWriteArrayList<>();
    remote.get =
        body -> {
          times.add(System.nanoTime());
          return json(times.size() < 3 ? pending(body) : body);
        };
    openStore().append("等待同一个操作", MemoryScope.CORE);
    assertOnePutAndSameIdLookup();
    assertThat(times).hasSize(3);
    for (int index = 1; index < times.size(); index++) {
      assertThat(Duration.ofNanos(times.get(index) - times.get(index - 1)))
          .isGreaterThanOrEqualTo(Duration.ofMillis(240));
    }
  }

  @Test
  void preservesInterruptAndUnknownOperationIdAfterDispatch() {
    LongTermMemoryStore store = openStore();
    Thread caller = Thread.currentThread();
    remote.put =
        body -> {
          caller.interrupt();
          return new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE);
        };
    try {
      MemoryOperationException error =
          failure(() -> store.append("中断不等于撤销", MemoryScope.CORE), Code.MEMORY_OUTCOME_UNKNOWN);
      assertThat(error.operationId()).isPresent();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(remote.results).hasSize(1);
    } finally {
      // 只在测试退出后清理测试线程，生产调用不得通过清除标志继续确认。
      Thread.interrupted();
    }
  }

  @Test
  void doesNotInheritAmbientProxySelector() {
    ProxySelector previous = ProxySelector.getDefault();
    AtomicBoolean used = new AtomicBoolean();
    List<StackTraceElement> proxyTrace = new CopyOnWriteArrayList<>();
    ProxySelector.setDefault(
        new ProxySelector() {
          @Override
          public List<Proxy> select(URI uri) {
            used.set(true);
            proxyTrace.addAll(List.of(Thread.currentThread().getStackTrace()));
            throw new IllegalStateException("不应继承进程代理");
          }

          @Override
          public void connectFailed(URI uri, SocketAddress address, IOException error) {}
        });
    try {
      Throwable error = catchThrowable(() -> assertThat(openStore().load()).isEmpty());
      assertThat(used).as("代理选择器调用栈：%s", proxyTrace).isFalse();
      assertThat(error).isNull();
    } finally {
      ProxySelector.setDefault(previous);
    }
  }

  @Test
  void rejectsSnapshotOlderThanItsConfirmedSave() {
    LongTermMemoryStore store = openStore();
    store.append("已确认的版本", MemoryScope.CORE);
    remote.snapshot = body -> json(body.put("revision", 0));
    failure(store::load, Code.MEMORY_PROTOCOL_ERROR);
  }

  @Test
  void rejectsSnapshotThatExpiresWhileThePageIsInFlight() {
    remote.seed("CORE", "过期快照不能注入上下文");
    remote.snapshot =
        body -> json(body.put("expires_at", Instant.now().plusSeconds(1).getEpochSecond()));
    remote.page = body -> json(body).setBodyDelay(2, TimeUnit.SECONDS);
    LongTermMemoryStore store =
        new Mem0MemoryStore(properties(Duration.ofSeconds(4)), guard, https.clientSslContext());
    failure(store::load, Code.MEMORY_PROTOCOL_ERROR);
  }

  private static ObjectNode pending(ObjectNode body) {
    return identity(body)
        .put("state", "RUNNING")
        .put("replayed", false)
        .put("deadline_at", Instant.now().plusSeconds(30).toString());
  }

  private Mem0Properties properties(Duration readTimeout) {
    return new Mem0Properties(
        https.uri("/").toString(),
        TOKEN,
        WORKSPACE,
        Duration.ofSeconds(1),
        readTimeout,
        Duration.ofSeconds(5));
  }

  private void assertOnePutAndSameIdLookup() {
    List<RecordedRequest> puts =
        remote.requests.stream().filter(r -> "PUT".equals(r.getMethod())).toList();
    assertThat(puts).hasSize(1);
    List<RecordedRequest> lookups =
        remote.requests.stream()
            .filter(r -> "GET".equals(r.getMethod()) && r.getPath().contains("/operations/"))
            .toList();
    assertThat(lookups)
        .isNotEmpty()
        .allSatisfy(r -> assertThat(r.getPath()).isEqualTo(puts.getFirst().getPath()));
  }

  private static MemoryOperationException failure(Runnable action, Code code) {
    Throwable error = catchThrowable(action::run);
    assertThat(error).isInstanceOf(MemoryOperationException.class);
    MemoryOperationException memoryError = (MemoryOperationException) error;
    assertThat(memoryError.code()).isEqualTo(code);
    return memoryError;
  }

  private static MockResponse broken(ObjectNode body, String fault) {
    switch (fault) {
      case "malformed" -> {
        return new MockResponse().setBody("{");
      }
      case "oversized" -> {
        return new MockResponse().setBody(" ".repeat(1048577));
      }
      case "server" -> {
        return new MockResponse().setResponseCode(503).setBody("远端敏感诊断");
      }
      case "denied" -> {
        return new MockResponse().setResponseCode(403).setBody("远端敏感诊断");
      }
      case "not_found" -> {
        return new MockResponse().setResponseCode(404);
      }
      case "expired" -> {
        return new MockResponse().setResponseCode(410);
      }
      case "disconnect" -> {
        return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);
      }
      case "hash" -> body.put("request_hash", "0".repeat(64));
      case "workspace" -> body.put("workspace_id", UUID.randomUUID().toString());
      case "operation" -> body.put("operation_id", UUID.randomUUID().toString());
      case "scope" -> body.put("scope", "ARCHIVAL");
      case "kind" -> body.put("kind", "RECALL");
      case "history" -> body.put("history_complete", false);
      case "missing_history" -> body.remove("history_complete");
      case "missing_receipt" -> body.removeAll();
      default -> throw new AssertionError("测试故障名称无效");
    }
    return json(body);
  }

  private static ObjectNode identity(ObjectNode body) {
    ObjectNode result = JSON.createObjectNode();
    for (String field : List.of("operation_id", "workspace_id", "kind", "scope", "request_hash")) {
      result.set(field, body.get(field));
    }
    return result;
  }

  private static MockResponse json(ObjectNode body) {
    body.put("request_id", UUID.randomUUID().toString());
    return new MockResponse()
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body.toString());
  }

  /** 仅替换HTTPS对端；测试始终调用真实Java Store，不模拟Store的方法. */
  private static final class ProtocolServer extends Dispatcher {
    final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    final List<String> rawInputs = new ArrayList<>();
    final Map<String, ObjectNode> results = new HashMap<>();
    final List<ObjectNode> entries = new ArrayList<>();
    final Map<String, List<ObjectNode>> snapshots = new HashMap<>();
    final List<String> requestScopes = new ArrayList<>();
    Function<ObjectNode, MockResponse> caps = Mem0MemoryStoreContractTest::json;
    Function<ObjectNode, MockResponse> put = Mem0MemoryStoreContractTest::json;
    Function<ObjectNode, MockResponse> get = Mem0MemoryStoreContractTest::json;
    Function<ObjectNode, MockResponse> page = Mem0MemoryStoreContractTest::json;
    Function<ObjectNode, MockResponse> snapshot = Mem0MemoryStoreContractTest::json;
    boolean reject;
    boolean noop;
    int pageSize = 100;
    long revision;

    @Override
    public synchronized MockResponse dispatch(RecordedRequest request) {
      requests.add(request);
      if (reject) {
        return new MockResponse().setResponseCode(403);
      }
      if (!("Bearer " + TOKEN).equals(request.getHeader("Authorization"))) {
        return new MockResponse().setResponseCode(401);
      }
      try {
        HttpUrl url = request.getRequestUrl();
        String path = url.encodedPath();
        if (path.equals(PREFIX + "/capabilities")) {
          ObjectNode body =
              JSON.createObjectNode()
                  .put("protocol", "oryx-memory-v1")
                  .put("schema_version", 1)
                  .put("sdk_version", "1.0.11+oryx.1")
                  .put("staged_engine", true)
                  .put("atomic_history", true)
                  .put("revision_pagination", true)
                  .put("build_version", "0".repeat(64))
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
          return caps.apply(body);
        }
        if (!path.startsWith(ROOT + "/")) {
          return new MockResponse().setResponseCode(403);
        }
        if (path.startsWith(ROOT + "/operations/")) {
          String id = url.pathSegments().getLast();
          if ("GET".equals(request.getMethod())) {
            ObjectNode receipt = results.get(id);
            return receipt == null
                ? new MockResponse().setResponseCode(404)
                : get.apply(receipt.deepCopy());
          }
          ObjectNode body = (ObjectNode) JSON.readTree(request.getBody().clone().readUtf8());
          ObjectNode receipt = operation(id, body);
          results.put(id, receipt.deepCopy());
          return put.apply(receipt.deepCopy());
        }
        if (path.equals(ROOT + "/snapshots")) {
          String token = "snapshot-" + UUID.randomUUID();
          snapshots.put(token, entries.stream().map(ObjectNode::deepCopy).toList());
          return snapshot.apply(
              JSON.createObjectNode()
                  .put("snapshot_id", token)
                  .put("revision", revision)
                  .put("expires_at", Instant.now().plusSeconds(300).getEpochSecond())
                  .put("core_count", selected(entries, "CORE").size())
                  .put("archival_count", selected(entries, "ARCHIVAL").size()));
        }
        if (path.endsWith("/entries")) {
          return page.apply(page(url));
        }
        return new MockResponse().setResponseCode(404);
      } catch (Exception error) {
        throw new AssertionError("HTTPS协议替身内部错误", error);
      }
    }

    synchronized void seed(String scope, String content) {
      revision++;
      entries.add(
          JSON.createObjectNode()
              .put("memory_id", UUID.randomUUID().toString())
              .put("version_id", UUID.randomUUID().toString())
              .put("scope", scope)
              .put("content", content)
              .put("created_revision", revision)
              .put("updated_revision", revision));
    }

    private ObjectNode operation(String id, ObjectNode body) throws Exception {
      String kind = body.path("kind").asText();
      String scope = kind.equals("RECALL") ? "ARCHIVAL" : body.path("scope").asText();
      String raw = body.path(kind.equals("SAVE") ? "content" : "query").asText();
      rawInputs.add(raw);
      byte[] bytes =
          ("oryx-memory-v1\0" + WORKSPACE + "\0" + kind + "\0" + scope + "\0" + raw)
              .getBytes(StandardCharsets.UTF_8);
      ObjectNode result =
          JSON.createObjectNode()
              .put("operation_id", id)
              .put("workspace_id", WORKSPACE)
              .put("kind", kind)
              .put("scope", scope)
              .put(
                  "request_hash",
                  HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
              .put("state", "COMMITTED")
              .put("committed_at", Instant.now().toString())
              .put("replayed", false);
      if (kind.equals("SAVE")) {
        if (!noop) {
          seed(scope, raw);
        } else {
          revision++;
        }
        result.put("outcome", noop ? "NOOP" : "CHANGED").put("history_complete", true);
        result.set(
            "action_counts",
            JSON.createObjectNode().put("ADD", noop ? 0 : 1).put("UPDATE", 0).put("DELETE", 0));
        ArrayNode ids = result.putArray("affected_ids");
        if (!noop) {
          ids.add(entries.getLast().path("memory_id").asText());
        }
      } else {
        ArrayNode hits = result.putArray("items");
        List<ObjectNode> archive = selected(entries, "ARCHIVAL");
        for (int index = 0; index < Math.min(20, archive.size()); index++) {
          ObjectNode entry = archive.get(index);
          ObjectNode hit = JSON.createObjectNode().put("score", 0.9 - index * 0.01);
          for (String field : List.of("memory_id", "version_id", "scope", "content")) {
            hit.set(field, entry.get(field));
          }
          hits.add(hit);
        }
        result
            .put("snapshot_revision", revision)
            .put("returned_count", hits.size())
            .put("truncated_by_bytes", false);
      }
      return result.put("revision", revision);
    }

    private ObjectNode page(HttpUrl url) {
      String token = url.pathSegments().get(url.pathSegments().size() - 2);
      String scope = url.queryParameter("scope");
      requestScopes.add(scope);
      List<ObjectNode> selected = selected(snapshots.get(token), scope);
      String cursor = url.queryParameter("cursor");
      if (cursor != null && !cursor.startsWith(scope + ":")) {
        throw new AssertionError("跨scope复用cursor");
      }
      int start = cursor == null ? 0 : Integer.parseInt(cursor.substring(scope.length() + 1));
      int end = Math.min(selected.size(), start + pageSize);
      ObjectNode result =
          JSON.createObjectNode()
              .put("snapshot_id", token)
              .put("revision", revision)
              .put("scope", scope)
              .put("total_count", selected.size())
              .put("complete", end == selected.size());
      if (end == selected.size()) {
        result.putNull("next_cursor");
      } else {
        result.put("next_cursor", scope + ":" + end);
      }
      ArrayNode items = result.putArray("items");
      selected.subList(start, end).forEach(item -> items.add(item.deepCopy()));
      return result;
    }

    private static List<ObjectNode> selected(List<ObjectNode> source, String scope) {
      List<ObjectNode> values =
          source.stream().filter(item -> scope.equals(item.path("scope").asText())).toList();
      return scope.equals("ARCHIVAL") && values.size() > 100
          ? values.subList(values.size() - 100, values.size())
          : values;
    }
  }
}
