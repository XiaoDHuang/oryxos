package com.oryxos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot configuration root. Started on demand by CLI commands that need LLM/Web (chat / serve
 * / gateway), not by {@code oryxos init}.
 *
 * @author OryxOS Contributors
 */
@SpringBootApplication(scanBasePackages = "com.oryxos")
public class OryxOsApplication {

  /** Starts the Spring runtime for local Web/Actuator verification. */
  public static void main(String[] args) {
    SpringApplication.run(OryxOsApplication.class, args);
  }
}
