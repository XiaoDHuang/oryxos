package com.oryxos.tool.mcp;

import com.oryxos.core.tool.OryxTool;
import com.oryxos.tool.ToolRegistry;
import com.oryxos.tool.sandbox.ActionType;
import com.oryxos.tool.sandbox.Sandbox;
import com.oryxos.tool.sandbox.SandboxAction;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * MCP按服务独立发现，失败不能留下部分工具或阻断其他服务.
 *
 * @author OryxOS Contributors
 */
public final class McpClientService implements AutoCloseable {
  static final Duration INITIALIZE_TIMEOUT = Duration.ofSeconds(10);
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
  static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(30);
  static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(10);
  private static final int MAX_PAGES = 32;
  private static final String SERVERS_FIELD = "servers";

  private static final Logger LOG = LoggerFactory.getLogger(McpClientService.class);
  private final Path configurationFile;
  private final ToolRegistry registry;
  private final Sandbox sandbox;
  private final Consumer<String> schemaValidator;
  private final BiConsumer<String, String> argumentValidator;
  private final BiFunction<McpServerConfig, SchemaPreservingMcpJsonMapper, McpSyncClient> factory;
  private final Map<String, String> environment;
  private final Duration discoveryTimeout;
  private final Duration cleanupTimeout;
  private final List<McpSyncClient> active = new ArrayList<>();
  private boolean connected;
  private boolean closed;

  /** 沿用固定发现预算和环境解析规则，不把内部时限扩散为应用配置. */
  public McpClientService(
      Path configurationFile,
      ToolRegistry registry,
      Sandbox sandbox,
      Consumer<String> schemaValidator,
      BiConsumer<String, String> argumentValidator) {
    this(
        configurationFile,
        registry,
        sandbox,
        schemaValidator,
        argumentValidator,
        McpClientService::newClient,
        System.getenv(),
        DISCOVERY_TIMEOUT,
        CLEANUP_TIMEOUT);
  }

  McpClientService(
      Path configurationFile,
      ToolRegistry registry,
      Sandbox sandbox,
      Consumer<String> schemaValidator,
      BiConsumer<String, String> argumentValidator,
      BiFunction<McpServerConfig, SchemaPreservingMcpJsonMapper, McpSyncClient> factory,
      Map<String, String> environment,
      Duration discoveryTimeout,
      Duration cleanupTimeout) {
    this.configurationFile = Objects.requireNonNull(configurationFile);
    this.registry = Objects.requireNonNull(registry);
    this.sandbox = Objects.requireNonNull(sandbox);
    this.schemaValidator = Objects.requireNonNull(schemaValidator);
    this.argumentValidator = Objects.requireNonNull(argumentValidator);
    this.factory = Objects.requireNonNull(factory);
    this.environment = Map.copyOf(environment);
    this.discoveryTimeout = Objects.requireNonNull(discoveryTimeout);
    this.cleanupTimeout = Objects.requireNonNull(cleanupTimeout);
    if (discoveryTimeout.isNegative()
        || discoveryTimeout.isZero()
        || cleanupTimeout.isNegative()
        || cleanupTimeout.isZero()) {
      throw new IllegalArgumentException("MCP内部时限必须为正数");
    }
  }

  /** 启动期只发现一次，清单变更不会触发热注册. */
  public synchronized void connectAll() {
    if (connected || closed) {
      return;
    }
    connected = true;
    List<?> entries = loadEntries();
    Map<String, Integer> counts = new HashMap<>(Math.max(16, entries.size() * 2));
    for (Object entry : entries) {
      if (entry instanceof Map<?, ?> values && values.get("name") instanceof String name) {
        counts.merge(name, 1, Integer::sum);
      }
    }
    for (Object entry : entries) {
      if (Thread.currentThread().isInterrupted()) {
        break;
      }
      String name = "未命名";
      try {
        if (entry instanceof Map<?, ?> values
            && values.get("name") instanceof String configuredName) {
          name = configuredName;
        }
        if (counts.getOrDefault(name, 0) > 1) {
          throw new IllegalArgumentException("MCP服务名称重复");
        }
        connect(McpServerConfig.from(entry, environment));
      } catch (RuntimeException exception) {
        warn(name, exception);
      }
    }
  }

  private List<?> loadEntries() {
    if (!Files.exists(configurationFile)) {
      return List.of();
    }
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    try (var input = Files.newBufferedReader(configurationFile)) {
      Object raw = new Yaml(new SafeConstructor(options)).load(input);
      if (!(raw instanceof Map<?, ?> root)
          || !(root.get(SERVERS_FIELD) instanceof List<?> entries)) {
        throw new IllegalArgumentException("MCP配置顶层必须为servers列表");
      }
      return entries;
    } catch (IOException | RuntimeException exception) {
      warn("配置文件", exception);
      return List.of();
    }
  }

  private void connect(McpServerConfig configuration) {
    SandboxAction action =
        new SandboxAction(ActionType.SHELL_EXEC, configuration.command().getFirst());
    sandbox.enforce(action);
    var mapper = new SchemaPreservingMcpJsonMapper(schemaValidator);
    mapper.beginDiscovery();
    AtomicReference<McpSyncClient> client = new AtomicReference<>();
    AtomicReference<List<OryxTool>> batch = new AtomicReference<>();
    AtomicReference<RuntimeException> failure = new AtomicReference<>();
    AtomicBoolean cancelled = new AtomicBoolean();
    AtomicBoolean clientClosed = new AtomicBoolean();
    long deadline = System.nanoTime() + discoveryTimeout.toNanos();
    Thread worker =
        Thread.ofVirtual()
            .name("oryxos-mcp-discovery")
            .unstarted(
                () -> {
                  try {
                    McpSyncClient created = factory.apply(configuration, mapper);
                    client.set(created);
                    if (cancelled.get()) {
                      return;
                    }
                    created.initialize();
                    batch.set(
                        discover(configuration, created, mapper, action, cancelled, deadline));
                  } catch (RuntimeException exception) {
                    failure.set(exception);
                  } finally {
                    if (cancelled.get()) {
                      closeOnce(client.get(), clientClosed);
                    }
                  }
                });
    boolean accepted = false;
    try {
      worker.start();
      awaitDiscovery(worker, deadline);
      if (failure.get() != null) {
        warnConfiguration(configuration, failure.get());
        return;
      }
      if (batch.get() == null || client.get() == null) {
        throw new IllegalStateException("MCP发现未返回完整清单");
      }
      // 工作线程不持有注册权，只有按时完成的完整批次才能由调用线程原子提交。
      registry.registerAll(batch.get());
      active.add(client.get());
      accepted = true;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      warn(configuration.name(), exception);
    } catch (RuntimeException exception) {
      warnConfiguration(configuration, exception);
    } finally {
      mapper.endDiscovery();
      if (!accepted) {
        cancelled.set(true);
        worker.interrupt();
        cleanupConnection(worker, client, clientClosed);
      }
    }
  }

  private static void awaitDiscovery(Thread worker, long deadline) throws InterruptedException {
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0 || !worker.join(Duration.ofNanos(remaining))) {
      throw new IllegalStateException("MCP发现超过总时限或已经取消");
    }
    if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) {
      throw new IllegalStateException("MCP发现超过总时限或已经取消");
    }
  }

  private static void warnConfiguration(McpServerConfig configuration, RuntimeException exception) {
    String safeName = configuration.name();
    for (String secret : configuration.sensitiveValues()) {
      if (!secret.isBlank()) {
        safeName = safeName.replace(secret, "[已脱敏]");
      }
    }
    warn(safeName, exception);
  }

  private void cleanupConnection(
      Thread worker, AtomicReference<McpSyncClient> client, AtomicBoolean clientClosed) {
    cleanup(
        () -> {
          closeOnce(client.get(), clientClosed);
          try {
            worker.join();
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
          }
          closeOnce(client.get(), clientClosed);
        });
  }

  private List<OryxTool> discover(
      McpServerConfig configuration,
      McpSyncClient client,
      SchemaPreservingMcpJsonMapper mapper,
      SandboxAction action,
      AtomicBoolean cancelled,
      long deadline) {
    List<OryxTool> tools = new ArrayList<>();
    Set<String> cursors = new HashSet<>();
    Set<String> names = new HashSet<>();
    String cursor = McpSchema.FIRST_PAGE;
    Set<String> secrets = configuration.sensitiveValues();
    for (int pageNumber = 0; pageNumber < MAX_PAGES; pageNumber++) {
      if (cancelled.get()
          || Thread.currentThread().isInterrupted()
          || System.nanoTime() >= deadline) {
        throw new IllegalStateException("MCP发现已经取消或超时");
      }
      McpSchema.ListToolsResult page = client.listTools(cursor);
      List<String> schemas = mapper.consumeSchemas(page);
      if (page.tools() == null
          || page.tools().size() != schemas.size()
          || tools.size() + page.tools().size() > 1000) {
        throw new IllegalArgumentException("MCP清单不完整或工具数超过上限");
      }
      for (int index = 0; index < page.tools().size(); index++) {
        McpSchema.Tool remote = page.tools().get(index);
        if (!names.add(remote.name())) {
          throw new IllegalArgumentException("MCP工具名称重复");
        }
        tools.add(
            new McpToolAdapter(
                configuration.name(),
                client,
                remote,
                schemas.get(index),
                sandbox,
                action,
                schemaValidator,
                argumentValidator,
                secrets));
      }
      String next = page.nextCursor();
      if (next == null || next.isEmpty()) {
        return List.copyOf(tools);
      }
      if (!cursors.add(next)) {
        throw new IllegalArgumentException("MCP分页游标循环");
      }
      cursor = next;
    }
    throw new IllegalArgumentException("MCP工具清单超过32页上限");
  }

  private static McpSyncClient newClient(
      McpServerConfig configuration, SchemaPreservingMcpJsonMapper mapper) {
    var parameters =
        ServerParameters.builder(configuration.command().getFirst())
            .args(configuration.command().subList(1, configuration.command().size()))
            .env(configuration.env())
            .build();
    var transport = new ConfiguredStdioTransport(parameters, mapper);
    transport.setStdErrorHandler(ignored -> LOG.warn("MCP子进程报告诊断信息，正文已省略"));
    return McpClient.sync(transport)
        .initializationTimeout(INITIALIZE_TIMEOUT)
        .requestTimeout(REQUEST_TIMEOUT)
        .enableCallToolSchemaCaching(false)
        .build();
  }

  private static void closeOnce(McpSyncClient client, AtomicBoolean closed) {
    if (client == null || !closed.compareAndSet(false, true)) {
      return;
    }
    try {
      if (!client.closeGracefully()) {
        client.close();
      }
    } catch (RuntimeException exception) {
      warn("关闭连接", exception);
      try {
        client.close();
      } catch (RuntimeException closeFailure) {
        warn("强制关闭连接", closeFailure);
      }
    }
  }

  private void cleanup(Runnable action) {
    boolean interrupted = Thread.interrupted();
    Thread cleanup = Thread.ofVirtual().name("oryxos-mcp-cleanup").start(action);
    try {
      if (!cleanup.join(cleanupTimeout)) {
        cleanup.interrupt();
        LOG.warn("MCP资源未在清理预算内全部退出");
      }
    } catch (InterruptedException exception) {
      interrupted = true;
      cleanup.interrupt();
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static void warn(String name, Exception exception) {
    LOG.warn(
        "MCP服务={}处理失败，异常类别={}",
        name.replace('\r', '_').replace('\n', '_'),
        exception.getClass().getSimpleName());
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    for (McpSyncClient client : active) {
      cleanup(() -> closeOnce(client, new AtomicBoolean()));
    }
    active.clear();
  }
}
