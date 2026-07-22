package com.oryxos.cli;

import picocli.CommandLine.Command;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Callable;

/**
 * Lightweight command: creates {@code .oryxos/} workspace without starting Spring.
 */
@Command(name = "init", description = "Initialize .oryxos workspace in the current directory")
public class InitCommand implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        Path root = Path.of(".oryxos");
        if (Files.exists(root)) {
            System.out.println(".oryxos already exists — skip");
            return 0;
        }

        Files.createDirectories(root.resolve("profiles"));
        Files.createDirectories(root.resolve("sessions"));
        Files.createDirectories(root.resolve("skills"));
        Files.createDirectories(root.resolve("logs"));
        Files.createDirectories(root.resolve("tools"));
        Files.createDirectories(root.resolve("memory"));

        // Placeholder DB file; tables are applied from classpath db/schema.sql on first Spring start (chat/serve).
        Files.writeString(root.resolve("oryxos.db"), "");

        Files.writeString(root.resolve("memory/MEMORY.md"), "# Long-term memory\n\n");
        Files.writeString(root.resolve("AGENTS.md"), "# Project agent guidelines\n\n");
        Files.writeString(root.resolve("SOUL.md"), "# Agent personality\n\n");
        Files.writeString(root.resolve("USER.md"), "# User preferences\n\n");
        Files.writeString(root.resolve("mcp_servers.yaml"), """
                # MCP servers (stdio). Example:
                # servers:
                #   - name: example
                #     transport: stdio
                #     command: ["npx", "-y", "example-mcp"]
                servers: []
                """);

        String now = Instant.now().toString();
        Files.writeString(root.resolve("profiles/default.yaml"), """
                name: default
                description: Default agent profile
                identity:
                  agent_name: oryxos-default
                  prompt: You are a helpful enterprise assistant.
                provider:
                  name: deepseek
                  model: deepseek-chat
                  temperature: 0.7
                tools:
                  - http_get
                  - http_post
                  - read_file
                  - write_file
                  - list_dir
                  - save_memory
                  - recall_memory
                skills: []
                mcp_servers: []
                channels:
                  - cli
                bootstrap:
                  - AGENTS.md
                  - SOUL.md
                  - USER.md
                settings:
                  max_iterations: 10
                  max_history_turns: 20
                created_at: %s
                updated_at: %s
                """.formatted(now, now));

        System.out.println("Initialized .oryxos workspace");
        return 0;
    }
}
