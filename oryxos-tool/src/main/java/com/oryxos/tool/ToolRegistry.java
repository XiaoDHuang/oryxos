package com.oryxos.tool;

import com.oryxos.core.profile.Profile;
import com.oryxos.core.tool.OryxTool;
import com.oryxos.tool.sandbox.SandboxViolationException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;

/**
 * 全部来源共用注册边界，避免覆盖名称或发布半份工具表.
 *
 * @author OryxOS Contributors
 */
public final class ToolRegistry {

  private final Map<String, OryxTool> tools = new LinkedHashMap<>();
  private final ToolArgumentValidator validator;
  private boolean frozen;

  /** 独立使用时仍应用同一参数规则，不依赖Spring上下文. */
  public ToolRegistry() {
    this(new ToolArgumentValidator());
  }

  ToolRegistry(ToolArgumentValidator validator) {
    this.validator = java.util.Objects.requireNonNull(validator);
  }

  /** 单个注册也经过原子校验，不能覆盖已经公开的工具. */
  public void register(OryxTool tool) {
    if (tool == null) {
      throw new IllegalArgumentException("工具不能为空");
    }
    registerAll(List.of(tool));
  }

  /** 一台服务的清单全部有效后才入表，避免半注册状态. */
  public synchronized void registerAll(List<? extends OryxTool> candidates) {
    if (frozen) {
      throw new IllegalStateException("工具表已经冻结");
    }
    if (candidates == null) {
      throw new IllegalArgumentException("工具列表不能为空");
    }
    Map<String, OryxTool> batch = new LinkedHashMap<>();
    for (OryxTool tool : candidates) {
      if (tool == null
          || tool.getName() == null
          || tool.getName().isBlank()
          || tool.getDescription() == null
          || tool.getDescription().isBlank()) {
        throw new IllegalArgumentException("工具名称和描述不能为空");
      }
      String name = tool.getName();
      if (tools.containsKey(name) || batch.containsKey(name)) {
        throw new IllegalArgumentException("工具名称重复: " + name);
      }
      validator.validateSchema(tool.getInputSchema());
      batch.put(name, tool);
    }
    tools.putAll(batch);
  }

  /** 发现插件不等于授予执行许可，默认guard在调用前拒绝. */
  public void registerAnnotated(Object bean) {
    registerAnnotated(
        bean,
        () -> {
          throw new SandboxViolationException("Java插件尚未获得执行许可");
        });
  }

  void registerAnnotated(Object bean, Runnable guard) {
    if (bean == null) {
      throw new IllegalArgumentException("工具Bean不能为空");
    }
    Map<Method, Tool> methods =
        MethodIntrospector.selectMethods(
            AopUtils.getTargetClass(bean),
            (MethodIntrospector.MetadataLookup<Tool>)
                method -> AnnotatedElementUtils.findMergedAnnotation(method, Tool.class));
    List<OryxTool> batch = new ArrayList<>();
    methods.keySet().stream()
        .sorted(Comparator.comparing(Method::toGenericString))
        .forEach(method -> batch.add(new AnnotatedToolAdapter(bean, method, validator, guard)));
    registerAll(batch);
  }

  /** 名称查询只检查元数据，不触发工具IO. */
  public synchronized boolean contains(String name) {
    return tools.containsKey(name);
  }

  /** 对外返回快照，避免调用方改变注册顺序或成员. */
  public synchronized List<OryxTool> all() {
    return List.copyOf(tools.values());
  }

  /** 按声明精确选择，缺工具时不能静默降级成另一组能力. */
  public synchronized List<OryxTool> forProfile(Profile profile) {
    if (profile == null) {
      throw new IllegalArgumentException("Profile不能为空");
    }
    var declared = new HashSet<String>();
    List<OryxTool> selected = new ArrayList<>();
    for (String name : profile.tools()) {
      if (!declared.add(name)) {
        throw new IllegalArgumentException("Profile重复声明工具: " + name);
      }
      OryxTool tool = tools.get(name);
      if (tool == null) {
        throw new IllegalArgumentException("Profile声明了未知工具: " + name);
      }
      selected.add(tool);
    }
    return List.copyOf(selected);
  }

  /** 引擎只消费不可变Map，保持core不依赖注册表实现. */
  public synchronized Map<String, OryxTool> asMap() {
    return Map.copyOf(tools);
  }

  synchronized void freeze() {
    frozen = true;
  }
}
