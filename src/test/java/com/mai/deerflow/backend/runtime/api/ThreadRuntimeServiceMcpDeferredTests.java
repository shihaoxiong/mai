package com.mai.deerflow.backend.runtime.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mai.deerflow.backend.p0.P0McpDemoServerMain;
import com.mai.deerflow.backend.runtime.agent.LeadAgentFactory;
import com.mai.deerflow.backend.runtime.agent.RuntimeAgentEnhancementService;
import com.mai.deerflow.backend.runtime.agent.RuntimeDeferredToolService;
import com.mai.deerflow.backend.runtime.agent.RuntimeLeadAgentPromptService;
import com.mai.deerflow.backend.runtime.artifact.ArtifactService;
import com.mai.deerflow.backend.runtime.checkpoint.RuntimeCheckpointProperties;
import com.mai.deerflow.backend.runtime.checkpoint.RuntimeCheckpointService;
import com.mai.deerflow.backend.runtime.config.FileRuntimeConfigRepository;
import com.mai.deerflow.backend.runtime.config.RuntimeConfigProperties;
import com.mai.deerflow.backend.runtime.contract.RunStatus;
import com.mai.deerflow.backend.runtime.contract.ThreadStateSnapshot;
import com.mai.deerflow.backend.runtime.event.ThreadEventService;
import com.mai.deerflow.backend.runtime.mcp.McpConfigService;
import com.mai.deerflow.backend.runtime.mcp.McpServerConfig;
import com.mai.deerflow.backend.runtime.mcp.RuntimeMcpToolCallback;
import com.mai.deerflow.backend.runtime.mcp.RuntimeMcpToolProvider;
import com.mai.deerflow.backend.runtime.memory.FileMemoryStore;
import com.mai.deerflow.backend.runtime.memory.MemoryExtractorJob;
import com.mai.deerflow.backend.runtime.memory.MemoryInjectionProperties;
import com.mai.deerflow.backend.runtime.memory.MemoryInjectionService;
import com.mai.deerflow.backend.runtime.memory.MemoryStoreProperties;
import com.mai.deerflow.backend.runtime.postrun.PostRunGenerationService;
import com.mai.deerflow.backend.runtime.skill.SkillRegistryService;
import com.mai.deerflow.backend.runtime.state.RunStateMachine;
import com.mai.deerflow.backend.runtime.subtask.SubTaskExecutor;
import com.mai.deerflow.backend.runtime.upload.DocumentMarkdownConversionService;
import com.mai.deerflow.backend.runtime.upload.UploadService;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceProperties;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ThreadRuntimeServiceMcpDeferredTests {

    @TempDir
    Path tempDir;

    @Test
    void shouldDiscoverAndExecuteRealMcpToolThroughToolSearch() {
        String exposedToolName = RuntimeMcpToolCallback.exposedName("demo", "mcp_reverse");
        Fixture fixture = fixture(new McpDeferredChatModel(exposedToolName));
        String threadId = "mcp-deferred-thread-" + UUID.randomUUID();

        try {
            fixture.threadRuntimeService().createThread(threadId);
            ThreadStateSnapshot snapshot = fixture.threadRuntimeService().runThread(threadId, "use the demo mcp tool via tool_search");

            assertThat(snapshot.runStatus()).isEqualTo(RunStatus.COMPLETED);
            assertThat(snapshot.messages()).isNotEmpty();
            assertThat(snapshot.messages().get(snapshot.messages().size() - 1).content())
                    .contains("mcpSchema=true")
                    .contains("MCP:ateb");
        }
        finally {
            fixture.threadRuntimeService().deleteThread(threadId);
            fixture.runtimeMcpToolProvider().close();
        }
    }

    private Fixture fixture(ChatModel chatModel) {
        ThreadWorkspaceProperties workspaceProperties = new ThreadWorkspaceProperties();
        workspaceProperties.setBaseDir(tempDir.resolve("threads"));
        ThreadWorkspaceService threadWorkspaceService = new ThreadWorkspaceService(workspaceProperties);
        DocumentMarkdownConversionService documentMarkdownConversionService = new DocumentMarkdownConversionService();
        UploadService uploadService = new UploadService(threadWorkspaceService, documentMarkdownConversionService);
        ArtifactService artifactService = new ArtifactService(threadWorkspaceService);

        RuntimeConfigProperties runtimeConfigProperties = new RuntimeConfigProperties();
        runtimeConfigProperties.setFile(tempDir.resolve("runtime-config.json"));
        FileRuntimeConfigRepository repository = new FileRuntimeConfigRepository(runtimeConfigProperties, new ObjectMapper());
        SkillRegistryService skillRegistryService = new SkillRegistryService(repository);
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

        MemoryStoreProperties memoryStoreProperties = new MemoryStoreProperties();
        memoryStoreProperties.setBaseDir(tempDir.resolve("memory"));
        FileMemoryStore fileMemoryStore = new FileMemoryStore(memoryStoreProperties, new ObjectMapper());
        MemoryInjectionService memoryInjectionService = new MemoryInjectionService(fileMemoryStore, new MemoryInjectionProperties());
        LeadAgentFactory leadAgentFactory = new LeadAgentFactory();
        RuntimeAgentEnhancementService runtimeAgentEnhancementService = new RuntimeAgentEnhancementService(new ObjectMapper());
        RuntimeLeadAgentPromptService runtimeLeadAgentPromptService = new RuntimeLeadAgentPromptService(
                threadWorkspaceService,
                uploadService,
                skillRegistryService
        );
        RuntimeDeferredToolService runtimeDeferredToolService = new RuntimeDeferredToolService(threadWorkspaceService, new ObjectMapper());
        RuntimeMcpToolProvider runtimeMcpToolProvider = new RuntimeMcpToolProvider(mcpConfigService, new ObjectMapper());
        runtimeDeferredToolService.setRuntimeMcpToolProvider(runtimeMcpToolProvider);
        runtimeLeadAgentPromptService.setRuntimeDeferredToolService(runtimeDeferredToolService);

        RuntimeCheckpointProperties runtimeCheckpointProperties = new RuntimeCheckpointProperties();
        runtimeCheckpointProperties.setBaseDir(tempDir.resolve("checkpoints"));
        RuntimeCheckpointService runtimeCheckpointService = new RuntimeCheckpointService(runtimeCheckpointProperties);
        ThreadEventService threadEventService = new ThreadEventService();
        MemoryExtractorJob memoryExtractorJob = new MemoryExtractorJob(fileMemoryStore, Runnable::run);
        SubTaskExecutor subTaskExecutor = new SubTaskExecutor(
                threadWorkspaceService,
                leadAgentFactory,
                chatModel,
                threadEventService,
                new ObjectMapper(),
                Runnable::run
        );

        ThreadRuntimeService threadRuntimeService = new ThreadRuntimeService(
                threadWorkspaceService,
                leadAgentFactory,
                runtimeAgentEnhancementService,
                runtimeLeadAgentPromptService,
                memoryInjectionService,
                chatModel,
                new RunStateMachine(),
                new ObjectMapper(),
                uploadService,
                artifactService,
                threadEventService,
                memoryExtractorJob,
                subTaskExecutor,
                new PostRunGenerationService(),
                runtimeCheckpointService
        );
        threadRuntimeService.setRuntimeDeferredToolService(runtimeDeferredToolService);
        return new Fixture(threadRuntimeService, runtimeMcpToolProvider);
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

    private static final class McpDeferredChatModel implements ChatModel {

        private final String exposedToolName;

        private McpDeferredChatModel(String exposedToolName) {
            this.exposedToolName = exposedToolName;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            List<ToolResponseMessage> toolResponses = messages.stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .toList();

            if (toolResponses.isEmpty()) {
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .content("Search mcp tool first")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "tool-search-1",
                                "function",
                                "tool_search",
                                "{\"query\":\"select:%s\"}".formatted(exposedToolName)
                        )))
                        .build())));
            }

            boolean hasSchema = toolResponses.stream()
                    .flatMap(response -> response.getResponses().stream())
                    .anyMatch(response -> "tool_search".equals(response.name()));
            boolean hasMcpResponse = toolResponses.stream()
                    .flatMap(response -> response.getResponses().stream())
                    .anyMatch(response -> exposedToolName.equals(response.name()));

            if (hasSchema && !hasMcpResponse) {
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .content("Call the actual MCP tool")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-mcp-1",
                                "function",
                                exposedToolName,
                                "{\"text\":\"beta\"}"
                        )))
                        .build())));
            }

            String schemaPayload = toolResponses.stream()
                    .flatMap(response -> response.getResponses().stream())
                    .filter(response -> "tool_search".equals(response.name()))
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .findFirst()
                    .orElse("");
            String mcpPayload = toolResponses.stream()
                    .flatMap(response -> response.getResponses().stream())
                    .filter(response -> exposedToolName.equals(response.name()))
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .findFirst()
                    .orElse("");

            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "mcpSchema=%s; mcpResult=%s".formatted(schemaPayload.contains(exposedToolName), mcpPayload)
            ))));
        }
    }

    private record Fixture(
            ThreadRuntimeService threadRuntimeService,
            RuntimeMcpToolProvider runtimeMcpToolProvider
    ) {
    }
}
