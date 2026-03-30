package com.mai.deerflow.backend.runtime.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 运行时 MCP 工具提供器。
 *
 * 当前首版能力：
 * 1. 根据平台层 `McpServerConfig` 建立 stdio MCP 连接
 * 2. 把远端 MCP tools 转换成 Spring AI `ToolCallback`
 * 3. 依据配置快照做最小缓存与失效
 */
@Service
public class RuntimeMcpToolProvider implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RuntimeMcpToolProvider.class);
    private static final Duration MCP_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MCP_INITIALIZATION_TIMEOUT = Duration.ofSeconds(10);

    private final McpConfigService mcpConfigService;
    private final ObjectMapper objectMapper;
    private final McpJsonMapper mcpJsonMapper;

    private String cachedFingerprint;
    private List<ToolCallback> cachedTools = List.of();
    private List<LoadedMcpServer> loadedServers = List.of();

    public RuntimeMcpToolProvider(McpConfigService mcpConfigService, ObjectMapper objectMapper) {
        this.mcpConfigService = mcpConfigService;
        this.objectMapper = objectMapper;
        this.mcpJsonMapper = McpJsonMapper.createDefault();
    }

    /**
     * 加载当前所有可用的 MCP tools；若配置未变化，则直接复用缓存。
     */
    public synchronized List<ToolCallback> loadTools() {
        List<McpServerConfig> configs = mcpConfigService.listServers().stream()
                .filter(McpServerConfig::enabled)
                .toList();
        String fingerprint = fingerprintOf(configs);
        if (Objects.equals(fingerprint, cachedFingerprint)) {
            return cachedTools;
        }

        closeLoadedServers();

        List<LoadedMcpServer> servers = new ArrayList<>();
        List<ToolCallback> tools = new ArrayList<>();
        for (McpServerConfig config : configs) {
            LoadedMcpServer loadedServer = loadServer(config);
            if (loadedServer == null) {
                continue;
            }
            servers.add(loadedServer);
            tools.addAll(loadedServer.tools());
        }

        this.loadedServers = List.copyOf(servers);
        this.cachedTools = List.copyOf(tools);
        this.cachedFingerprint = fingerprint;
        return cachedTools;
    }

    /**
     * 主动清空缓存，供测试或未来配置热更新时使用。
     */
    public synchronized void reset() {
        closeLoadedServers();
        this.loadedServers = List.of();
        this.cachedTools = List.of();
        this.cachedFingerprint = null;
    }

    @Override
    public synchronized void close() {
        reset();
    }

    private LoadedMcpServer loadServer(McpServerConfig config) {
        if (!"stdio".equalsIgnoreCase(config.transport())) {
            logger.warn("Unsupported MCP transport '{}' for server {}, current runtime only supports stdio", config.transport(), config.id());
            return null;
        }
        if (config.command() == null || config.command().isBlank()) {
            logger.warn("Skipping MCP server {} because command is blank", config.id());
            return null;
        }

        try {
            ServerParameters serverParameters = ServerParameters.builder(config.command().trim())
                    .args(config.args() == null ? List.of() : config.args())
                    .env(config.env() == null ? java.util.Map.of() : config.env())
                    .build();
            StdioClientTransport transport = new StdioClientTransport(serverParameters, mcpJsonMapper);
            transport.setStdErrorHandler(message -> {
            });

            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(MCP_REQUEST_TIMEOUT)
                    .initializationTimeout(MCP_INITIALIZATION_TIMEOUT)
                    .build();
            client.initialize();

            List<ToolCallback> tools = client.listTools().tools().stream()
                    .map(tool -> RuntimeMcpToolCallback.from(config.id(), client, mcpJsonMapper, tool))
                    .map(ToolCallback.class::cast)
                    .toList();

            logger.info("Loaded {} MCP tools from server {}", tools.size(), config.id());
            return new LoadedMcpServer(config.id(), transport, client, tools);
        }
        catch (Exception exception) {
            logger.warn("Failed to initialize MCP server {}", config.id(), exception);
            return null;
        }
    }

    private void closeLoadedServers() {
        for (LoadedMcpServer loadedServer : loadedServers) {
            try {
                loadedServer.client().closeGracefully();
            }
            catch (Exception exception) {
                logger.debug("Failed to close MCP client for {}", loadedServer.id(), exception);
            }
            try {
                loadedServer.transport().closeGracefully().block(MCP_REQUEST_TIMEOUT);
            }
            catch (Exception exception) {
                logger.debug("Failed to close MCP transport for {}", loadedServer.id(), exception);
            }
            try {
                loadedServer.transport().awaitForExit();
            }
            catch (Exception exception) {
                logger.debug("Failed to await MCP transport exit for {}", loadedServer.id(), exception);
            }
        }
    }

    private String fingerprintOf(List<McpServerConfig> configs) {
        try {
            return objectMapper.writeValueAsString(configs);
        }
        catch (Exception exception) {
            throw new IllegalStateException("Failed to fingerprint MCP configuration", exception);
        }
    }

    private record LoadedMcpServer(
            String id,
            StdioClientTransport transport,
            McpSyncClient client,
            List<ToolCallback> tools
    ) {
    }
}
