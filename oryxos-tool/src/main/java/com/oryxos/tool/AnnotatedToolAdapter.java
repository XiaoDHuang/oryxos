package com.oryxos.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.oryxos.core.tool.OryxTool;
import com.oryxos.core.tool.ToolResult;
import com.oryxos.memory.MemoryOperationException;
import com.oryxos.tool.sandbox.SandboxViolationException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;

/**
 * 注解只提供元数据，执行权由OryxOS保留.
 *
 * @author OryxOS Contributors
 */
public final class AnnotatedToolAdapter implements OryxTool {

  private final Object bean;
  private final Method method;
  private final Parameter[] parameters;
  private final ToolArgumentValidator validator;
  private final Runnable guard;
  private final String name;
  private final String description;
  private final String inputSchema;

  /** 注解不构成插件的权限声明，生产默认拒绝调用. */
  public AnnotatedToolAdapter(Object bean, Method method) {
    this(
        bean,
        method,
        new ToolArgumentValidator(),
        () -> {
          throw new SandboxViolationException("Java插件尚未获得执行许可");
        });
  }

  AnnotatedToolAdapter(
      Object bean, Method method, ToolArgumentValidator validator, Runnable guard) {
    this.bean = Objects.requireNonNull(bean, "工具Bean不能为空");
    Objects.requireNonNull(method, "工具方法不能为空");
    this.validator = Objects.requireNonNull(validator, "参数校验器不能为空");
    this.guard = Objects.requireNonNull(guard, "执行许可检查不能为空");
    Tool annotation = AnnotatedElementUtils.findMergedAnnotation(method, Tool.class);
    if (annotation == null || annotation.description().isBlank()) {
      throw new IllegalArgumentException("工具方法必须声明非空的Tool描述");
    }
    if (!Modifier.isPublic(method.getModifiers())
        || Modifier.isStatic(method.getModifiers())
        || asynchronous(method.getReturnType())
        || method.isVarArgs()) {
      throw new IllegalArgumentException("工具只接受公开的同步定参实例方法");
    }
    name = annotation.name().isBlank() ? method.getName() : annotation.name();
    description = annotation.description();
    parameters = method.getParameters();
    for (Parameter parameter : parameters) {
      if (!parameter.isNamePresent()) {
        throw new IllegalArgumentException("工具参数必须保留编译期名称");
      }
    }
    this.method = AopUtils.selectInvocableMethod(method, bean.getClass());
    ReflectionUtils.makeAccessible(this.method);
    inputSchema = JsonSchemaGenerator.generateForMethodInput(method);
    validator.validateSchema(inputSchema);
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String getDescription() {
    return description;
  }

  @Override
  public String getInputSchema() {
    return inputSchema;
  }

  @Override
  public ToolResult execute(String argumentsJson) {
    try {
      validator.validateArguments(inputSchema, argumentsJson);
      // DTO构造也可能执行插件代码，许可检查必须先于参数绑定。
      guard.run();
      JsonNode arguments = validator.readArguments(argumentsJson);
      Object[] values = new Object[parameters.length];
      for (int index = 0; index < parameters.length; index++) {
        Parameter parameter = parameters[index];
        values[index] =
            validator
                .mapper()
                .convertValue(
                    arguments.get(parameter.getName()),
                    validator.mapper().constructType(parameter.getParameterizedType()));
      }
      return convertResult(method.invoke(bean, values));
    } catch (SandboxViolationException exception) {
      throw exception;
    } catch (InvocationTargetException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof SandboxViolationException violation) {
        throw violation;
      }
      if (cause instanceof MemoryOperationException memoryFailure) {
        return ToolResult.fail(name, memoryFailure.safeMessage(), false);
      }
      if (cause instanceof InterruptedException) {
        Thread.currentThread().interrupt();
        return ToolResult.fail(name, "工具执行已中断");
      }
      return ToolResult.fail(name, "工具方法执行失败");
    } catch (IllegalArgumentException exception) {
      return ToolResult.fail(name, "工具参数校验或绑定失败");
    } catch (ReflectiveOperationException | JsonProcessingException exception) {
      return ToolResult.fail(name, "工具调用或结果转换失败");
    }
  }

  private ToolResult convertResult(Object value) throws JsonProcessingException {
    if (value instanceof ToolResult result) {
      return name.equals(result.toolName()) ? result : ToolResult.fail(name, "工具返回的名称不匹配");
    }
    if (value == null) {
      return ToolResult.ok(name, "");
    }
    return ToolResult.ok(
        name, value instanceof String text ? text : validator.mapper().writeValueAsString(value));
  }

  private static boolean asynchronous(Class<?> type) {
    if (Future.class.isAssignableFrom(type)
        || CompletionStage.class.isAssignableFrom(type)
        || Flow.Publisher.class.isAssignableFrom(type)
        || "org.reactivestreams.Publisher".equals(type.getName())) {
      return true;
    }
    for (Class<?> contract : type.getInterfaces()) {
      if (asynchronous(contract)) {
        return true;
      }
    }
    return type.getSuperclass() != null && asynchronous(type.getSuperclass());
  }
}
