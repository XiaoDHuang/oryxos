package com.oryxos;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot configuration root. Started on demand by CLI commands that need LLM/Web
 * (chat / serve / gateway), not by {@code oryxos init}.
 */
@SpringBootApplication(scanBasePackages = "com.oryxos")
public class OryxOsApplication {
}
