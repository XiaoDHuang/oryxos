package com.oryxos.memory;

import static java.net.HttpURLConnection.HTTP_FORBIDDEN;
import static java.net.HttpURLConnection.HTTP_GATEWAY_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;

import com.oryxos.core.memory.MemoryScope;
import com.oryxos.memory.MemoryOperationException.Code;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;

/**
 * 保存只接受匹配的持久终态；读取完整快照，不缓存记忆正文或回退到本地后端.
 *
 * @author OryxOS Contributors
 */
public final class Mem0MemoryStore implements LongTermMemoryStore {
  private static final String PUT = "PUT";
  private static final String CORE = "CORE";
  private static final String ARCHIVAL = "ARCHIVAL";

  private final Mem0Properties properties;
  private final Mem0HttpTransport transport;
  private final String workspacePath;

  /** 只保存本实例已确认版本的读取下界，不持有唯一业务状态或正文副本. */
  private final AtomicLong confirmedRevision = new AtomicLong();

  /** 生产入口不允许配置不安全的证书校验器. */
  public Mem0MemoryStore(Mem0Properties properties, MemoryOutboundGuard guard) {
    this(properties, guard, null);
  }

  /** 测试仅注入专属可信CA，不改变主机名验证规则. */
  Mem0MemoryStore(Mem0Properties properties, MemoryOutboundGuard guard, SSLContext tls) {
    transport = new Mem0HttpTransport(properties, guard, tls);
    this.properties = properties;
    workspacePath = Mem0Protocol.PREFIX + "/workspaces/" + properties.workspaceId();
    Mem0HttpTransport.Response response =
        transport.exchange(
            "GET", Mem0Protocol.PREFIX + "/capabilities", null, null, deadline(), () -> {});
    requireReadSuccess(response.status());
    Mem0Protocol.capabilities(Mem0Protocol.parse(response.body()));
  }

  @Override
  public void append(String content, MemoryScope scope) {
    Mem0Protocol.Operation operation =
        Mem0Protocol.operation(properties.workspaceId(), "SAVE", scope, content);
    AtomicBoolean dispatched = new AtomicBoolean();
    long deadline = deadline();
    Mem0Protocol.Receipt receipt = null;
    try {
      receipt = exchangeOperation(PUT, operation, deadline, () -> dispatched.set(true));
    } catch (MemoryOperationException exception) {
      if (!dispatched.get()) {
        throw exception;
      }
    }
    receipt = confirm(operation, receipt, deadline, true);
    finish(receipt, operation);
    confirmedRevision.accumulateAndGet(receipt.revision(), Math::max);
  }

  @Override
  public String load() {
    long deadline = deadline();
    long minimumRevision = confirmedRevision.get();
    Mem0HttpTransport.Response response =
        transport.exchange(
            "POST",
            workspacePath + "/snapshots",
            "{}".getBytes(StandardCharsets.UTF_8),
            null,
            deadline,
            () -> {});
    requireReadSuccess(response.status());
    Mem0Protocol.Snapshot snapshot = Mem0Protocol.snapshot(Mem0Protocol.parse(response.body()));
    if (snapshot.revision() < minimumRevision) {
      throw Mem0Protocol.invalid();
    }
    List<String> sections = new ArrayList<>();
    Set<String> allIds = new HashSet<>();
    appendSection(
        sections, "## 核心记忆", pages(snapshot, CORE, snapshot.coreCount(), deadline, allIds));
    appendSection(
        sections, "## 归档记忆", pages(snapshot, ARCHIVAL, snapshot.archivalCount(), deadline, allIds));
    return String.join("\n\n", sections);
  }

  @Override
  public List<String> recall(String query) {
    Mem0Protocol.Operation operation =
        Mem0Protocol.operation(properties.workspaceId(), "RECALL", MemoryScope.ARCHIVAL, query);
    long deadline = deadline();
    Mem0Protocol.Receipt receipt = exchangeOperation(PUT, operation, deadline, () -> {});
    receipt = confirm(operation, receipt, deadline, false);
    finish(receipt, operation);
    return receipt.items();
  }

  private Mem0Protocol.Receipt exchangeOperation(
      String method, Mem0Protocol.Operation operation, long deadline, Runnable dispatched) {
    Mem0HttpTransport.Response response =
        transport.exchange(
            method,
            workspacePath + "/operations/" + operation.id(),
            PUT.equals(method) ? operation.body() : null,
            operation.id(),
            deadline,
            dispatched);
    // 先尝试完整持久凭据，HTTP失败本身不是确定未提交的证据。
    try {
      return Mem0Protocol.receipt(
          Mem0Protocol.parse(response.body()), operation, response.status());
    } catch (MemoryOperationException exception) {
      if (response.status() == HTTP_UNAUTHORIZED || response.status() == HTTP_FORBIDDEN) {
        throw new MemoryOperationException(Code.MEMORY_ACCESS_DENIED, operation.id());
      }
      if (response.status() >= HTTP_INTERNAL_ERROR) {
        throw new MemoryOperationException(Code.MEMORY_SERVICE_FAILURE, operation.id());
      }
      throw new MemoryOperationException(Code.MEMORY_PROTOCOL_ERROR, operation.id());
    }
  }

  private Mem0Protocol.Receipt confirm(
      Mem0Protocol.Operation operation, Mem0Protocol.Receipt receipt, long deadline, boolean save) {
    while (receipt == null || receipt.pending()) {
      if (Thread.currentThread().isInterrupted() || Mem0HttpTransport.remaining(deadline) <= 0) {
        throw unconfirmed(operation, save);
      }
      try {
        long sleep =
            Math.min(TimeUnit.MILLISECONDS.toNanos(250), Mem0HttpTransport.remaining(deadline));
        TimeUnit.NANOSECONDS.sleep(Math.max(1, sleep));
        receipt = exchangeOperation("GET", operation, deadline, () -> {});
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw unconfirmed(operation, save);
      } catch (MemoryOperationException exception) {
        if (!save) {
          throw exception;
        }
        if (exception.code() == Code.MEMORY_ACCESS_DENIED
            || Thread.currentThread().isInterrupted()) {
          throw unconfirmed(operation, true);
        }
        receipt = null;
      }
    }
    return receipt;
  }

  private static void finish(Mem0Protocol.Receipt receipt, Mem0Protocol.Operation operation) {
    if (receipt.failure() != null) {
      throw new MemoryOperationException(receipt.failure(), operation.id());
    }
  }

  private List<String> pages(
      Mem0Protocol.Snapshot snapshot,
      String scope,
      long expected,
      long deadline,
      Set<String> allIds) {
    List<String> contents = new ArrayList<>();
    Set<String> cursors = new HashSet<>();
    String cursor = null;
    Mem0Protocol.Entry previous = null;
    String pathPrefix =
        workspacePath
            + "/snapshots/"
            + encode(snapshot.token())
            + "/entries?scope="
            + scope
            + "&page_size=100";
    do {
      requireCurrentSnapshot(snapshot);
      StringBuilder path = new StringBuilder(pathPrefix);
      if (cursor != null) {
        path.append("&cursor=").append(encode(cursor));
      }
      Mem0HttpTransport.Response response =
          transport.exchange("GET", path.toString(), null, null, deadline, () -> {});
      requireReadSuccess(response.status());
      requireCurrentSnapshot(snapshot);
      Mem0Protocol.Page page =
          Mem0Protocol.page(Mem0Protocol.parse(response.body()), snapshot, scope, expected);
      for (Mem0Protocol.Entry item : page.items()) {
        if (!allIds.add(item.id())) {
          throw Mem0Protocol.invalid();
        }
        if (!ordered(previous, item, scope)) {
          throw Mem0Protocol.invalid();
        }
        contents.add(item.content());
        previous = item;
      }
      boolean wrongCompleteCount = page.complete() && contents.size() != expected;
      boolean wrongPartialCount = !page.complete() && contents.size() >= expected;
      if (contents.size() > expected || wrongCompleteCount || wrongPartialCount) {
        throw Mem0Protocol.invalid();
      }
      cursor = page.cursor();
      if (cursor != null && !cursors.add(cursor)) {
        throw Mem0Protocol.invalid();
      }
    } while (cursor != null);
    return contents;
  }

  private static boolean ordered(
      Mem0Protocol.Entry previous, Mem0Protocol.Entry item, String scope) {
    if (previous == null) {
      return true;
    }
    if (CORE.equals(scope)) {
      int comparison = Long.compare(item.created(), previous.created());
      boolean increasingTie = comparison == 0 && item.id().compareTo(previous.id()) > 0;
      return comparison > 0 || increasingTie;
    }
    return item.updated() >= previous.updated();
  }

  private static void requireCurrentSnapshot(Mem0Protocol.Snapshot snapshot) {
    if (Instant.now().getEpochSecond() >= snapshot.expiresAt()) {
      throw Mem0Protocol.invalid();
    }
  }

  private static void appendSection(List<String> sections, String title, List<String> contents) {
    if (!contents.isEmpty()) {
      sections.add(title + "\n" + String.join("\n", contents));
    }
  }

  private static MemoryOperationException unconfirmed(
      Mem0Protocol.Operation operation, boolean save) {
    return new MemoryOperationException(
        save ? Code.MEMORY_OUTCOME_UNKNOWN : Code.MEMORY_TIMEOUT, operation.id());
  }

  private static void requireReadSuccess(int status) {
    if (status == HTTP_OK) {
      return;
    }
    Code code =
        status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN
            ? Code.MEMORY_ACCESS_DENIED
            : status == HTTP_GATEWAY_TIMEOUT
                ? Code.MEMORY_TIMEOUT
                : status >= HTTP_INTERNAL_ERROR
                    ? Code.MEMORY_SERVICE_FAILURE
                    : Code.MEMORY_PROTOCOL_ERROR;
    throw new MemoryOperationException(code);
  }

  private long deadline() {
    return Mem0HttpTransport.deadline(properties.operationTimeout());
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
