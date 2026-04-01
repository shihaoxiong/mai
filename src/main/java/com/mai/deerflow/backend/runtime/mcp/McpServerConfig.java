package com.mai.deerflow.backend.runtime.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP Server 的平台配置描述。
 */
public record McpServerConfig(
        String id,
        boolean enabled,
        String transport,
        String command,
        List<String> args,
        Map<String, String> env,
        String url,
        String endpoint,
        String sseEndpoint,
        Map<String, String> headers
) {

    public McpServerConfig(String id,
                           boolean enabled,
                           String transport,
                           String command,
                           List<String> args,
                           Map<String, String> env) {
        this(id, enabled, transport, command, args, env, null, null, null, Map.of());
    }
}
