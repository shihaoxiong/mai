package com.mai.deerflow.backend.runtime.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 运行时 MCP 工具提供器。
 *
 * 当前首版能力：
 * 1. 根据平台层 `McpServerConfig` 建立 stdio / HTTP(SSE) / streamable HTTP MCP 连接
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
        String transport = normalizeTransport(config.transport());
        return switch (transport) {
            case "stdio" -> loadStdioServer(config);
            case "sse" -> loadSseServer(config);
            case "streamable-http", "streamable_http", "http" -> loadStreamableHttpServer(config);
            default -> {
                logger.warn("Unsupported MCP transport '{}' for server {}", config.transport(), config.id());
                yield null;
            }
        };
    }

    private LoadedMcpServer loadStdioServer(McpServerConfig config) {
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
            return initializeServer(config.id(), transport);
        }
        catch (Exception exception) {
            logger.warn("Failed to initialize MCP server {}", config.id(), exception);
            return null;
        }
    }

    private LoadedMcpServer loadSseServer(McpServerConfig config) {
        if (!hasText(config.url())) {
            logger.warn("Skipping MCP server {} because url is blank", config.id());
            return null;
        }

        try {
            HttpClientSseClientTransport.Builder builder = HttpClientSseClientTransport.builder(config.url().trim())
                    .jsonMapper(mcpJsonMapper)
                    .connectTimeout(MCP_INITIALIZATION_TIMEOUT)
                    .requestBuilder(httpRequestBuilder(config));
            if (hasText(config.sseEndpoint())) {
                builder.sseEndpoint(config.sseEndpoint().trim());
            }
            return initializeServer(config.id(), builder.build());
        }
        catch (Exception exception) {
            logger.warn("Failed to initialize SSE MCP server {}", config.id(), exception);
            return null;
        }
    }

    private LoadedMcpServer loadStreamableHttpServer(McpServerConfig config) {
        if (!hasText(config.url())) {
            logger.warn("Skipping MCP server {} because url is blank", config.id());
            return null;
        }

        try {
            HttpClientStreamableHttpTransport.Builder builder = HttpClientStreamableHttpTransport.builder(config.url().trim())
                    .jsonMapper(mcpJsonMapper)
                    .connectTimeout(MCP_INITIALIZATION_TIMEOUT)
                    .requestBuilder(httpRequestBuilder(config))
                    .openConnectionOnStartup(false)
                    .resumableStreams(true);
            if (hasText(config.endpoint())) {
                builder.endpoint(config.endpoint().trim());
            }

            HttpClientStreamableHttpTransport transport = builder.build();
            transport.setExceptionHandler(throwable ->
                    logger.debug("Observed streamable HTTP MCP transport error for {}", config.id(), throwable));
            return initializeServer(config.id(), transport);
        }
        catch (Exception exception) {
            logger.warn("Failed to initialize streamable HTTP MCP server {}", config.id(), exception);
            return null;
        }
    }

    private LoadedMcpServer initializeServer(String serverId, McpClientTransport transport) {
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(MCP_REQUEST_TIMEOUT)
                .initializationTimeout(MCP_INITIALIZATION_TIMEOUT)
                .build();
        client.initialize();

        List<ToolCallback> tools = client.listTools().tools().stream()
                .map(tool -> RuntimeMcpToolCallback.from(serverId, client, mcpJsonMapper, tool))
                .map(ToolCallback.class::cast)
                .toList();

        logger.info("Loaded {} MCP tools from server {}", tools.size(), serverId);
        return new LoadedMcpServer(serverId, transport, client, tools);
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
            if (loadedServer.transport() instanceof StdioClientTransport stdioClientTransport) {
                try {
                    stdioClientTransport.awaitForExit();
                }
                catch (Exception exception) {
                    logger.debug("Failed to await MCP transport exit for {}", loadedServer.id(), exception);
                }
            }
        }
    }

    private HttpRequest.Builder httpRequestBuilder(McpServerConfig config) {
        HttpRequest.Builder builder = HttpRequest.newBuilder();
        for (Map.Entry<String, String> entry : safeHeaders(config).entrySet()) {
            if (!hasText(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            builder.header(entry.getKey().trim(), entry.getValue());
        }
        return builder;
    }

    private Map<String, String> safeHeaders(McpServerConfig config) {
        return config.headers() == null ? Map.of() : config.headers();
    }

    private String normalizeTransport(String transport) {
        return transport == null ? "" : transport.trim().toLowerCase();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
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
            McpClientTransport transport,
            McpSyncClient client,
            List<ToolCallback> tools
    ) {
    }
}
