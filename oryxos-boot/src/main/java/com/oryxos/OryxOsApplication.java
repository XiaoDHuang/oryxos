package com.oryxos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Spring Boot 配置根. 由需要 LLM/Web 的 CLI 命令(chat / serve / gateway)按需启动,{@code oryxos init}
 * 等轻命令不走这里。JPA 扫描包显式声明:组件扫描(scanBasePackages)与自动配置的 Repository/Entity 扫描是两套独立逻辑,不显式写
 * basePackages,跨模块时会得到 "Found 0 JPA repository interfaces"(课件点名的真实踩坑)。
 *
 * @author OryxOS Contributors
 */
@SpringBootApplication(scanBasePackages = "com.oryxos")
@EnableJpaRepositories(basePackages = "com.oryxos.storage")
@EntityScan(basePackages = "com.oryxos.storage")
public class OryxOsApplication {

  /** 启动 Spring 运行时,用于本地 Web/Actuator 验证. */
  public static void main(String[] args) {
    SpringApplication.run(OryxOsApplication.class, args);
  }
}
