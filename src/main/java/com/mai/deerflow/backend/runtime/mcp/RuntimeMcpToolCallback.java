package com.mai.deerflow.backend.runtime.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.stream.Collectors;

/**
 * 把 MCP tool 包装成 Spring AI `ToolCallback`。
 */
public final class RuntimeMcpToolCallback implements ToolCallback {

    private final McpSyncClient client;
    private final McpJsonMapper jsonMapper;
    private final String originalName;
    private final ToolDefinition toolDefinition;

    private RuntimeMcpToolCallback(McpSyncClient client,
                                   McpJsonMapper jsonMapper,
                                   String originalName,
                                   ToolDefinition toolDefinition) {
        this.client = client;
        this.jsonMapper = jsonMapper;
        this.originalName = originalName;
        this.toolDefinition = toolDefinition;
    }

    static RuntimeMcpToolCallback from(String serverId,
                                       McpSyncClient client,
                                       McpJsonMapper jsonMapper,
                                       McpSchema.Tool tool) {
        String exposedName = exposedName(serverId, tool.name());
        try {
            ToolDefinition toolDefinition = DefaultToolDefinition.builder()
                    .name(exposedName)
                    .description(description(serverId, tool))
                    .inputSchema(jsonMapper.writeValueAsString(tool.inputSchema()))
                    .build();
            return new RuntimeMcpToolCallback(client, jsonMapper, tool.name(), toolDefinition);
        }
        catch (Exception exception) {
            throw new IllegalStateException("Failed to convert MCP tool definition for " + tool.name(), exception);
        }
    }

    public static String exposedName(String serverId, String originalName) {
        String normalizedServerId = serverId == null || serverId.isBlank()
                ? "mcp"
                : serverId.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
        return normalizedServerId + "__" + originalName;
    }

    private static String description(String serverId, McpSchema.Tool tool) {
        String baseDescription = tool.description() == null ? "" : tool.description().trim();
        if (baseDescription.isBlank()) {
            return "MCP tool from server " + serverId + ".";
        }
        return baseDescription + " (MCP server: " + serverId + ")";
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return ToolMetadata.builder().build();
    }

    @Override
    public String call(String toolInput) {
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name(originalName)
                .arguments(jsonMapper, toolInput)
                .build();

        McpSchema.CallToolResult result = client.callTool(request);
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .collect(Collectors.joining("\n"));
    }
}
