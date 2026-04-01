package com.mai.deerflow.backend.runtime.mcp;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ToolAndMcpIntegrationTests {

    @Test
    void leadAgentShouldCallLocalToolAndMcpTool() throws Exception {
        ToolCallback localTool = FunctionToolCallback
                .builder("local_echo", (LocalEchoInput input) -> "LOCAL:" + input.text())
                .description("Echo text from a local built-in tool.")
                .inputType(LocalEchoInput.class)
                .build();

        try (McpDemoClient mcpDemoClient = McpDemoClient.start()) {
            ToolCallback mcpTool = new McpSyncToolCallback(mcpDemoClient.client(), mcpDemoClient.jsonMapper(), "mcp_reverse");

            ReactAgent reactAgent = ReactAgent.builder()
                    .name("tool-and-mcp-agent")
                    .instruction("Use tools when they are available.")
                    .model(new DualToolCallingChatModel())
                    .tools(localTool, mcpTool)
                    .saver(new MemorySaver())
                    .releaseThread(false)
                    .build();

            AssistantMessage assistantMessage = reactAgent.call("call both tools");

            assertThat(assistantMessage.getText())
                    .contains("LOCAL:alpha")
                    .contains("MCP:ateb");
        }
    }

    record LocalEchoInput(String text) {
    }

    private static final class DualToolCallingChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();

            List<ToolResponseMessage> toolResponses = messages.stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .toList();

            if (toolResponses.isEmpty()) {
                AssistantMessage toolCallMessage = AssistantMessage.builder()
                        .content("Calling local and MCP tools")
                        .toolCalls(List.of(
                                new AssistantMessage.ToolCall("call-local", "function", "local_echo", "{\"text\":\"alpha\"}"),
                                new AssistantMessage.ToolCall("call-mcp", "function", "mcp_reverse", "{\"text\":\"beta\"}")
                        ))
                        .build();

                return new ChatResponse(List.of(new Generation(toolCallMessage)));
            }

            Map<String, String> toolOutputs = toolResponses.stream()
                    .flatMap(message -> message.getResponses().stream())
                    .collect(Collectors.toMap(
                            ToolResponseMessage.ToolResponse::name,
                            ToolResponseMessage.ToolResponse::responseData,
                            (left, right) -> right
                    ));

            AssistantMessage finalMessage = new AssistantMessage(
                    "local=" + toolOutputs.get("local_echo") + "; mcp=" + toolOutputs.get("mcp_reverse")
            );

            return new ChatResponse(List.of(new Generation(finalMessage)));
        }
    }

    private static final class McpDemoClient implements AutoCloseable {

        private final StdioClientTransport transport;
        private final McpSyncClient client;
        private final McpJsonMapper jsonMapper;

        private McpDemoClient(StdioClientTransport transport, McpSyncClient client, McpJsonMapper jsonMapper) {
            this.transport = transport;
            this.client = client;
            this.jsonMapper = jsonMapper;
        }

        static McpDemoClient start() {
            McpJsonMapper jsonMapper = McpJsonMapper.createDefault();
            StdioClientTransport transport = new StdioClientTransport(serverParameters(), jsonMapper);
            transport.setStdErrorHandler(message -> {
            });
            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(10))
                    .initializationTimeout(Duration.ofSeconds(10))
                    .build();
            client.initialize();
            return new McpDemoClient(transport, client, jsonMapper);
        }

        private static ServerParameters serverParameters() {
            String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            String logbackConfig = Path.of("target", "test-classes", "mcp-demo-logback.xml").toAbsolutePath().toString();

            return ServerParameters.builder(javaExecutable)
                    .args(
                            "-Dlogback.configurationFile=" + logbackConfig,
                            "-cp",
                            classpath,
                            McpDemoServerMain.class.getName()
                    )
                    .build();
        }

        McpSyncClient client() {
            return client;
        }

        McpJsonMapper jsonMapper() {
            return jsonMapper;
        }

        @Override
        public void close() {
            client.closeGracefully();
            transport.awaitForExit();
        }
    }

    private static final class McpSyncToolCallback implements ToolCallback {

        private final McpSyncClient mcpSyncClient;
        private final McpJsonMapper jsonMapper;
        private final ToolDefinition toolDefinition;

        private McpSyncToolCallback(McpSyncClient mcpSyncClient, McpJsonMapper jsonMapper, String toolName) {
            this.mcpSyncClient = mcpSyncClient;
            this.jsonMapper = jsonMapper;
            this.toolDefinition = toolDefinition(mcpSyncClient, jsonMapper, toolName);
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
                    .name(toolDefinition.name())
                    .arguments(jsonMapper, toolInput)
                    .build();

            McpSchema.CallToolResult result = mcpSyncClient.callTool(request);

            return result.content().stream()
                    .filter(McpSchema.TextContent.class::isInstance)
                    .map(McpSchema.TextContent.class::cast)
                    .map(McpSchema.TextContent::text)
                    .collect(Collectors.joining("\n"));
        }

        private static ToolDefinition toolDefinition(McpSyncClient client, McpJsonMapper jsonMapper, String toolName) {
            McpSchema.Tool tool = client.listTools().tools().stream()
                    .filter(candidate -> toolName.equals(candidate.name()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("MCP tool not found: " + toolName));

            try {
                return DefaultToolDefinition.builder()
                        .name(tool.name())
                        .description(tool.description())
                        .inputSchema(jsonMapper.writeValueAsString(tool.inputSchema()))
                        .build();
            }
            catch (Exception exception) {
                throw new IllegalStateException("Failed to convert MCP tool definition", exception);
            }
        }
    }
}
