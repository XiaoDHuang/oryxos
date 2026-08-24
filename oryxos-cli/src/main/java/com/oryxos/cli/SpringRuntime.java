package com.oryxos.cli;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 重命令(chat/serve/gateway)拉起 Spring 运行时的唯一入口. 主类名按字符串引用:启动主类在 oryxos-boot(技术方案 §10 的模块职责),而
 * oryxos-boot 聚合依赖本模块——编译期直接 import 会形成 Maven 循环依赖。按名加载让职责分层不变;主类缺失时(比如单独跑 cli 模块)立刻给出可操作的中文错误。
 *
 * @author OryxOS Contributors
 */
final class SpringRuntime {

  private static final String APPLICATION_CLASS = "com.oryxos.OryxOsApplication";

  private SpringRuntime() {}

  /** 以指定模式(是否带 Web 容器)启动 Spring 上下文. */
  static ConfigurableApplicationContext start(boolean web, String... args) {
    Class<?> source = loadApplicationClass();
    return new SpringApplicationBuilder(source)
        .web(web ? WebApplicationType.SERVLET : WebApplicationType.NONE)
        .run(args);
  }

  private static Class<?> loadApplicationClass() {
    try {
      return Class.forName(APPLICATION_CLASS);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("运行时主类不在 classpath —— 请使用 oryxos-boot 打包的完整程序运行该命令", e);
    }
  }
}
