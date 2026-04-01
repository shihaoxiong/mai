package com.mai.deerflow.backend.runtime.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

public final class McpDemoServerMain {

    private McpDemoServerMain() {
    }

    public static void main(String[] args) {
        McpJsonMapper jsonMapper = McpJsonMapper.createDefault();

        McpSchema.Tool reverseTool = McpSchema.Tool.builder()
                .name("mcp_reverse")
                .description("Reverse the provided text through the MCP demo server.")
                .inputSchema(jsonMapper, """
                        {
                          "type": "object",
                          "properties": {
                            "text": {
                              "type": "string"
                            }
                          },
                          "required": ["text"]
                        }
                        """)
                .build();

        McpServer.sync(new StdioServerTransportProvider(jsonMapper))
                .serverInfo("mcp-demo-server", "0.0.1")
                .tool(reverseTool, (exchange, arguments) -> {
                    String text = String.valueOf(arguments.getOrDefault("text", ""));
                    String reversed = new StringBuilder(text).reverse().toString();
                    return McpSchema.CallToolResult.builder()
                            .addTextContent("MCP:" + reversed)
                            .build();
                })
                .build();

        try {
            Thread.sleep(Long.MAX_VALUE);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
