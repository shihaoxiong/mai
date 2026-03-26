package com.mai.deerflow.backend.runtime.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mai.deerflow.backend.runtime.config.FileRuntimeConfigRepository;
import com.mai.deerflow.backend.runtime.config.RuntimeConfigProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpConfigServiceTests {

    @TempDir
    Path tempDir;

    private McpConfigService mcpConfigService;

    @BeforeEach
    void setUp() {
        RuntimeConfigProperties properties = new RuntimeConfigProperties();
        properties.setFile(tempDir.resolve("runtime-config.json"));
        FileRuntimeConfigRepository repository = new FileRuntimeConfigRepository(properties, new ObjectMapper());
        mcpConfigService = new McpConfigService(repository);
    }

    @Test
    void shouldReturnEmptyListWhenNoMcpConfigExists() {
        assertThat(mcpConfigService.listServers()).isEmpty();
    }

    @Test
    void shouldPersistAndLoadMcpServerConfigs() {
        List<McpServerConfig> configs = List.of(
                new McpServerConfig(
                        "filesystem",
                        true,
                        "stdio",
                        "npx",
                        List.of("-y", "@modelcontextprotocol/server-filesystem", "."),
                        Map.of("NODE_ENV", "test")
                )
        );

        mcpConfigService.replaceServers(configs);

        assertThat(mcpConfigService.listServers()).containsExactlyElementsOf(configs);
    }
}
