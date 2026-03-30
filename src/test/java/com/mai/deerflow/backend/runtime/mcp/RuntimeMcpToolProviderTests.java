package com.mai.deerflow.backend.runtime.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mai.deerflow.backend.p0.P0McpDemoServerMain;
import com.mai.deerflow.backend.runtime.config.FileRuntimeConfigRepository;
import com.mai.deerflow.backend.runtime.config.RuntimeConfigProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeMcpToolProviderTests {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadAndExecuteRealMcpToolFromRuntimeConfig() {
        RuntimeConfigProperties properties = new RuntimeConfigProperties();
        properties.setFile(tempDir.resolve("runtime-config.json"));
        FileRuntimeConfigRepository repository = new FileRuntimeConfigRepository(properties, new ObjectMapper());
        McpConfigService mcpConfigService = new McpConfigService(repository);

        mcpConfigService.replaceServers(List.of(new McpServerConfig(
                "demo",
                true,
                "stdio",
                javaExecutable(),
                List.of(
                        "-Dlogback.configurationFile=" + logbackConfig(),
                        "-cp",
                        classpath(),
                        P0McpDemoServerMain.class.getName()
                ),
                Map.of()
        )));

        RuntimeMcpToolProvider provider = new RuntimeMcpToolProvider(mcpConfigService, new ObjectMapper());
        try {
            List<ToolCallback> tools = provider.loadTools();

            assertThat(tools).hasSize(1);
            assertThat(tools.get(0).getToolDefinition().name())
                    .isEqualTo(RuntimeMcpToolCallback.exposedName("demo", "mcp_reverse"));
            assertThat(tools.get(0).call("{\"text\":\"beta\"}")).isEqualTo("MCP:ateb");
        }
        finally {
            provider.close();
        }
    }

    private String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private String classpath() {
        return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    }

    private String logbackConfig() {
        return Path.of("target", "test-classes", "p0-mcp-logback.xml").toAbsolutePath().toString();
    }
}
