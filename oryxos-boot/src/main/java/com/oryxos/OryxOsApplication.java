package com.oryxos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 配置根. 由需要 LLM/Web 的 CLI 命令(chat / serve / gateway)按需启动, {@code oryxos init} 不启动它。
 *
 * @author OryxOS Contributors
 */
@SpringBootApplication(scanBasePackages = "com.oryxos")
public class OryxOsApplication {

  /** 启动 Spring 运行时,用于本地 Web/Actuator 验证. */
  public static void main(String[] args) {
    SpringApplication.run(OryxOsApplication.class, args);
  }
}
