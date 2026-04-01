package com.mai.deerflow.backend.runtime.agent;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mai.deerflow.backend.runtime.upload.DocumentMarkdownConversionService;
import com.mai.deerflow.backend.runtime.upload.UploadService;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceProperties;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeDeferredToolServiceTests {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void toolSearchShouldReturnDeferredToolDefinitions() throws Exception {
        RuntimeDeferredToolService service = service(tempDir.resolve("tool-search"));
        List<ToolCallback> deferredTools = service.deferredTools("tool-search-thread");
        ToolCallback toolSearch = service.toolSearchTool("tool-search-thread", deferredTools);

        String result = toolSearch.call("{\"query\":\"select:list_thread_files\"}");
        JsonNode root = objectMapper.readTree(result);

        assertThat(root.isArray()).isTrue();
        assertThat(root).hasSize(1);
        assertThat(root.get(0).get("name").asText()).isEqualTo("list_thread_files");
        assertThat(root.get(0).get("parameters")).isNotNull();
    }

    @Test
    void deferredToolFilterShouldHideDeferredToolsUntilToolSearchSelectsThem() {
        RuntimeDeferredToolFilterInterceptor interceptor = new RuntimeDeferredToolFilterInterceptor(
                List.of("list_thread_files", "read_thread_file"),
                objectMapper
        );

        AtomicReference<ModelRequest> capturedRequest = new AtomicReference<>();
        ModelRequest initialRequest = ModelRequest.builder()
                .systemMessage(new SystemMessage("system"))
                .messages(List.of(new UserMessage("list files")))
                .tools(List.of("ask_clarification", "tool_search", "list_thread_files", "read_thread_file"))
                .toolDescriptions(Map.of(
                        "ask_clarification", "clarify",
                        "tool_search", "search",
                        "list_thread_files", "list files",
                        "read_thread_file", "read file"
                ))
                .build();

        interceptor.interceptModel(initialRequest, request -> {
            capturedRequest.set(request);
            return ModelResponse.of(new AssistantMessage("ok"));
        });

        assertThat(capturedRequest.get().getTools())
                .containsExactly("ask_clarification", "tool_search");

        ToolResponseMessage toolSearchResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "search-1",
                        "tool_search",
                        """
                        [{"name":"list_thread_files","description":"list","parameters":{"type":"object"}}]
                        """
                )))
                .build();

        ModelRequest selectedRequest = ModelRequest.builder()
                .systemMessage(new SystemMessage("system"))
                .messages(List.of(new UserMessage("list files"), toolSearchResponse))
                .tools(List.of("ask_clarification", "tool_search", "list_thread_files", "read_thread_file"))
                .toolDescriptions(initialRequest.getToolDescriptions())
                .build();

        interceptor.interceptModel(selectedRequest, request -> {
            capturedRequest.set(request);
            return ModelResponse.of(new AssistantMessage("ok"));
        });

        assertThat(capturedRequest.get().getTools())
                .containsExactly("ask_clarification", "tool_search", "list_thread_files");
    }

    @Test
    void promptShouldListAvailableDeferredToolNames() throws Exception {
        ThreadWorkspaceProperties workspaceProperties = new ThreadWorkspaceProperties();
        workspaceProperties.setBaseDir(tempDir.resolve("prompt/threads"));
        ThreadWorkspaceService threadWorkspaceService = new ThreadWorkspaceService(workspaceProperties);
        UploadService uploadService = new UploadService(threadWorkspaceService, new DocumentMarkdownConversionService());
        com.mai.deerflow.backend.runtime.config.RuntimeConfigProperties runtimeConfigProperties =
                new com.mai.deerflow.backend.runtime.config.RuntimeConfigProperties();
        runtimeConfigProperties.setFile(tempDir.resolve("prompt/runtime-config.json"));
        RuntimeLeadAgentPromptService promptService = new RuntimeLeadAgentPromptService(
                threadWorkspaceService,
                uploadService,
                new com.mai.deerflow.backend.runtime.skill.SkillRegistryService(
                        new com.mai.deerflow.backend.runtime.config.FileRuntimeConfigRepository(
                                runtimeConfigProperties,
                                objectMapper
                        )
                )
        );
        promptService.setRuntimeDeferredToolService(service(tempDir.resolve("prompt/deferred")));

        String threadId = "prompt-deferred-thread";
        Files.createDirectories(threadWorkspaceService.getOrCreateWorkspace(threadId).workspaceRoot());
        String systemPrompt = promptService.systemPrompt(threadId);

        assertThat(systemPrompt)
                .contains("<available-deferred-tools>")
                .contains("list_thread_files")
                .contains("read_thread_file");
    }

    private RuntimeDeferredToolService service(Path root) throws Exception {
        ThreadWorkspaceProperties workspaceProperties = new ThreadWorkspaceProperties();
        workspaceProperties.setBaseDir(root.resolve("threads"));
        ThreadWorkspaceService threadWorkspaceService = new ThreadWorkspaceService(workspaceProperties);
        Files.createDirectories(threadWorkspaceService.getOrCreateWorkspace("tool-search-thread").uploadsRoot());
        return new RuntimeDeferredToolService(threadWorkspaceService, objectMapper);
    }
}
