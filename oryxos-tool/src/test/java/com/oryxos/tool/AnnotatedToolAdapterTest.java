package com.oryxos.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oryxos.core.tool.ToolResult;
import com.oryxos.memory.MemoryOperationException;
import com.oryxos.memory.MemoryOperationException.Code;
import com.oryxos.tool.sandbox.SandboxViolationException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.execution.ToolCallResultConverter;
import org.springframework.aop.framework.ProxyFactory;

@DisplayName("注解工具共享执行契约")
class AnnotatedToolAdapterTest {

  private final Fixture bean = new Fixture();

  @Test
  @DisplayName("名称描述与对象参数Schema来自原始方法")
  void publishesMetadata() throws Exception {
    AnnotatedToolAdapter adapter = trusted("echo", String.class);
    assertEquals("echo_text", adapter.getName());
    assertEquals("回显文本", adapter.getDescription());
    var schema = new ObjectMapper().readTree(adapter.getInputSchema());
    assertEquals("object", schema.path("type").asText());
    assertEquals("string", schema.path("properties").path("text").path("type").asText());
    assertEquals("text", schema.path("required").get(0).asText());
  }

  @Test
  @DisplayName("字符串结果直接成为内容且方法仅调用一次")
  void invokesStringOnce() throws Exception {
    var result = trusted("echo", String.class).execute("{\"text\":\"你好\"}");
    assertTrue(result.success());
    assertEquals("你好", result.content());
    assertEquals(1, bean.calls.get());
  }

  @Test
  @DisplayName("普通对象转JSON而空返回转空内容")
  void convertsObjectAndVoid() throws Exception {
    assertEquals("{\"answer\":42}", trusted("object").execute("{}").content());
    assertEquals("", trusted("nothing").execute("{}").content());
    assertTrue(trusted("nothing").execute("{}").success());
  }

  @Test
  @DisplayName("DTO和泛型容器保留参数类型")
  void bindsGenericInputs() throws Exception {
    var result =
        trusted("total", Payload.class, List.class)
            .execute("{\"payload\":{\"value\":2},\"values\":[3,4]}");
    assertTrue(result.success());
    assertEquals("9", result.content());
  }

  @Test
  @DisplayName("非法必填类型未知字段与重复键不进入方法体")
  void rejectsArgumentsBeforeInvocation() throws Exception {
    AnnotatedToolAdapter adapter = trusted("echo", String.class);
    for (String json :
        List.of(
            "{}",
            "{\"text\":1}",
            "{\"text\":\"a\",\"extra\":1}",
            "{\"text\":\"a\",\"text\":\"b\"}",
            "[]")) {
      assertFalse(adapter.execute(json).success(), json);
    }
    assertEquals(0, bean.calls.get());
  }

  @Test
  @DisplayName("明确返回的失败重试类别不丢失")
  void preservesToolResult() throws Exception {
    var result = trusted("retry").execute("{}");
    assertFalse(result.success());
    assertTrue(result.retryable());
    assertEquals("稍后再试", result.errorMessage());
    assertFalse(trusted("wrongName").execute("{}").success());
  }

  @Test
  @DisplayName("未知异常不泄漏秘密也不猜测可重试")
  void sanitizesInvocationFailure() throws Exception {
    var result = trusted("broken").execute("{}");
    assertFalse(result.success());
    assertFalse(result.retryable());
    assertFalse(result.errorMessage().contains("secret-token"));
  }

  @Test
  @DisplayName("记忆受限异常保留固定分类和操作编号且明确不可重试")
  void mapsMemoryFailureWithoutLeakingOrRetrying() throws Exception {
    var timeout = trusted("memoryTimeout").execute("{}");
    assertFalse(timeout.success());
    assertFalse(timeout.retryable());
    assertEquals(
        "MEMORY_TIMEOUT：记忆操作超时；operationId=11111111-1111-4111-8111-111111111111",
        timeout.errorMessage());

    var unknown = trusted("memoryUnknown").execute("{}");
    assertFalse(unknown.success());
    assertFalse(unknown.retryable());
    assertEquals(
        "MEMORY_OUTCOME_UNKNOWN：记忆保存结果不确定，请勿重复保存；operationId="
            + "11111111-1111-4111-8111-111111111111",
        unknown.errorMessage());
  }

  @Test
  @DisplayName("沙箱拒绝转为不可重试失败且原因可读")
  void mapsSandboxViolationToReadableFailure() throws Exception {
    var result = trusted("denied").execute("{}");
    assertFalse(result.success());
    assertFalse(result.retryable());
    assertEquals("禁止操作", result.errorMessage());
  }

  @Test
  @DisplayName("插件默认拒绝发生在DTO构造与方法体之前")
  void guardsBeforeBinding() throws Exception {
    Payload.constructed.set(0);
    AnnotatedToolAdapter adapter =
        new AnnotatedToolAdapter(bean, Fixture.class.getMethod("total", Payload.class, List.class));
    var result = adapter.execute("{\"payload\":{\"value\":2},\"values\":[3]}");
    assertFalse(result.success());
    assertFalse(result.retryable());
    assertTrue(result.errorMessage().contains("执行许可"));
    assertEquals(0, Payload.constructed.get());
    assertEquals(0, bean.calls.get());
  }

  @Test
  @DisplayName("静态非公开异步及无注解方法不得注册")
  void rejectsUnsupportedMethods() throws Exception {
    for (String name : List.of("staticCall", "hidden", "async", "unannotated", "blank")) {
      Method method = Fixture.class.getDeclaredMethod(name);
      assertThrows(
          IllegalArgumentException.class, () -> new AnnotatedToolAdapter(bean, method), name);
    }
  }

  @Test
  @DisplayName("方法中断被保留且不会重试")
  void preservesInterruption() throws Exception {
    try {
      var result = trusted("interrupted").execute("{}");
      assertFalse(result.success());
      assertFalse(result.retryable());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  private AnnotatedToolAdapter trusted(String name, Class<?>... parameterTypes) throws Exception {
    return new AnnotatedToolAdapter(
        bean, Fixture.class.getMethod(name, parameterTypes), new ToolArgumentValidator(), () -> {});
  }

  @Test
  @DisplayName("JDK和类代理方法仍由OryxOS调用且不绕过代理")
  void invokesJdkAndClassProxies() throws Exception {
    for (boolean classProxy : List.of(false, true)) {
      ProxyFixture target = new ProxyFixture();
      AtomicInteger adviceCalls = new AtomicInteger();
      ProxyFactory factory = new ProxyFactory(target);
      factory.setProxyTargetClass(classProxy);
      factory.addAdvice(
          (org.aopalliance.intercept.MethodInterceptor)
              invocation -> {
                adviceCalls.incrementAndGet();
                return invocation.proceed();
              });
      Object proxy = factory.getProxy();
      var adapter =
          new AnnotatedToolAdapter(
              proxy,
              ProxyFixture.class.getMethod("echo", String.class),
              new ToolArgumentValidator(),
              () -> {});
      assertEquals("通过代理", adapter.execute("{\"text\":\"通过代理\"}").content());
      assertEquals(1, adviceCalls.get());
    }
  }

  @Test
  @DisplayName("returnDirect与框架转换器不能夺取执行控制")
  void ignoresFrameworkExecutionOptions() throws Exception {
    var adapter =
        new AnnotatedToolAdapter(
            new FrameworkFixture(),
            FrameworkFixture.class.getMethod("call"),
            new ToolArgumentValidator(),
            () -> {});
    assertEquals("原始内容", adapter.execute("{}").content());
  }

  public interface ProxyContract {
    @Tool(name = "proxy_echo", description = "代理工具")
    String echo(String text);
  }

  public static class ProxyFixture implements ProxyContract {
    @Override
    @Tool(name = "proxy_echo", description = "代理工具")
    public String echo(String text) {
      return text;
    }
  }

  public static class FrameworkFixture {
    @Tool(description = "忽略框架执行选项", returnDirect = true, resultConverter = ForbiddenConverter.class)
    public String call() {
      return "原始内容";
    }
  }

  public static class ForbiddenConverter implements ToolCallResultConverter {
    @Override
    public String convert(Object value, Type type) {
      throw new AssertionError("不得调用框架结果转换器");
    }
  }

  public record Payload(int value) {
    static final AtomicInteger constructed = new AtomicInteger();

    public Payload {
      constructed.incrementAndGet();
    }
  }

  public static class Fixture {
    final AtomicInteger calls = new AtomicInteger();

    @Tool(name = "echo_text", description = "回显文本")
    public String echo(@ToolParam(description = "输入文本") String text) {
      calls.incrementAndGet();
      return text;
    }

    @Tool(description = "返回对象")
    public Map<String, Integer> object() {
      return Map.of("answer", 42);
    }

    @Tool(description = "返回空值")
    public void nothing() {}

    @Tool(description = "计算总和")
    public int total(Payload payload, List<Integer> values) {
      calls.incrementAndGet();
      return payload.value() + values.stream().mapToInt(Integer::intValue).sum();
    }

    @Tool(description = "明确瞬态失败")
    public ToolResult retry() {
      return ToolResult.fail("retry", "稍后再试", true);
    }

    @Tool(description = "结果工具名错误")
    public ToolResult wrongName() {
      return ToolResult.ok("other", "");
    }

    @Tool(description = "未知失败")
    public String broken() {
      throw new IllegalStateException("secret-token");
    }

    @Tool(description = "记忆超时")
    public String memoryTimeout() {
      throw new MemoryOperationException(
          Code.MEMORY_TIMEOUT, UUID.fromString("11111111-1111-4111-8111-111111111111"));
    }

    @Tool(description = "记忆结果未知")
    public String memoryUnknown() {
      throw new MemoryOperationException(
          Code.MEMORY_OUTCOME_UNKNOWN, UUID.fromString("11111111-1111-4111-8111-111111111111"));
    }

    @Tool(description = "安全拒绝")
    public String denied() {
      throw new SandboxViolationException("禁止操作");
    }

    @Tool(description = "中断调用")
    public String interrupted() throws InterruptedException {
      throw new InterruptedException();
    }

    @Tool(description = "静态方法")
    public static String staticCall() {
      return "";
    }

    @Tool(description = "非公开方法")
    private String hidden() {
      return "";
    }

    @Tool(description = "异步方法")
    public CompletableFuture<String> async() {
      return CompletableFuture.completedFuture("");
    }

    public String unannotated() {
      return "";
    }

    @Tool
    public String blank() {
      return "";
    }
  }
}
