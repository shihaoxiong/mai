package com.mai.deerflow.backend.runtime.api;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mai.deerflow.backend.runtime.agent.LeadAgentFactory;
import com.mai.deerflow.backend.runtime.agent.RuntimeAgentEnhancementService;
import com.mai.deerflow.backend.runtime.artifact.ArtifactService;
import com.mai.deerflow.backend.runtime.checkpoint.RuntimeCheckpointProperties;
import com.mai.deerflow.backend.runtime.checkpoint.RuntimeCheckpointService;
import com.mai.deerflow.backend.runtime.event.ThreadEventService;
import com.mai.deerflow.backend.runtime.graph.RuntimeGraphFactory;
import com.mai.deerflow.backend.runtime.graph.RuntimeStateKeys;
import com.mai.deerflow.backend.runtime.contract.TodoItem;
import com.mai.deerflow.backend.runtime.memory.FileMemoryStore;
import com.mai.deerflow.backend.runtime.memory.MemoryExtractorJob;
import com.mai.deerflow.backend.runtime.memory.MemoryInjectionProperties;
import com.mai.deerflow.backend.runtime.memory.MemoryInjectionService;
import com.mai.deerflow.backend.runtime.memory.MemoryStoreProperties;
import com.mai.deerflow.backend.runtime.postrun.PostRunGenerationService;
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
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class ThreadRuntimeCheckpointTests {

    @TempDir
    Path tempDir;

    @Test
    void shouldReuseLeadAgentConversationHistoryAcrossRuns() {
        ThreadRuntimeServiceFixture fixture = fixture(new PromptCountingChatModel(), tempDir.resolve("reuse"));
        String threadId = "checkpoint-history-" + UUID.randomUUID();

        try {
            fixture.threadRuntimeService.createThread(threadId);
            fixture.threadRuntimeService.runThread(threadId, "hello deerflow");
            fixture.threadRuntimeService.runThread(threadId, "follow up question");

            OverAllState checkpointState = runtimeState(fixture, threadId).orElseThrow();
            assertThat(checkpointState.value(RuntimeStateKeys.ASSISTANT_OUTPUT, String.class))
                    .hasValueSatisfying(output -> assertThat(output)
                            .contains("userMessages=2")
                            .contains("assistantMessages=1")
                            .contains("lastUser=follow up question"));
        }
        finally {
            fixture.threadRuntimeService.deleteThread(threadId);
        }
    }

    @Test
    void shouldClearCheckpointStateWhenThreadIsDeleted() {
        ThreadRuntimeServiceFixture fixture = fixture(new PromptCountingChatModel(), tempDir.resolve("delete"));
        String threadId = "checkpoint-delete-" + UUID.randomUUID();

        fixture.threadRuntimeService.createThread(threadId);
        fixture.threadRuntimeService.runThread(threadId, "first run");
        fixture.threadRuntimeService.deleteThread(threadId);

        fixture.threadRuntimeService.createThread(threadId);
        fixture.threadRuntimeService.runThread(threadId, "fresh again");

        OverAllState checkpointState = runtimeState(fixture, threadId).orElseThrow();
        assertThat(checkpointState.value(RuntimeStateKeys.ASSISTANT_OUTPUT, String.class))
                .hasValueSatisfying(output -> assertThat(output)
                        .contains("userMessages=1")
                        .contains("assistantMessages=0")
                        .contains("lastUser=fresh again"));

        fixture.threadRuntimeService.deleteThread(threadId);
    }

    @Test
    void shouldProjectWriteTodosToolResultIntoRuntimeCheckpointState() {
        ThreadRuntimeServiceFixture fixture = fixture(new PlanningChatModel(), tempDir.resolve("todos"));
        String threadId = "checkpoint-todos-" + UUID.randomUUID();

        try {
            fixture.threadRuntimeService.createThread(threadId);
            fixture.threadRuntimeService.runThread(threadId, "plan this work");

            OverAllState checkpointState = runtimeState(fixture, threadId).orElseThrow();
            List<?> todos = checkpointState.value(RuntimeStateKeys.TODOS, List.class).orElse(List.of());
            List<String> titles = todos.stream()
                    .map(todo -> {
                        if (todo instanceof TodoItem todoItem) {
                            return todoItem.title();
                        }
                        if (todo instanceof Map<?, ?> todoMap) {
                            return String.valueOf(todoMap.get("title"));
                        }
                        return String.valueOf(todo);
                    })
                    .toList();

            assertThat(todos).hasSize(2);
            assertThat(titles).containsExactly("Read uploaded brief", "Draft summary");
        }
        finally {
            fixture.threadRuntimeService.deleteThread(threadId);
        }
    }

    private ThreadRuntimeServiceFixture fixture(ChatModel chatModel, Path rootDir) {
        ThreadWorkspaceProperties workspaceProperties = new ThreadWorkspaceProperties();
        workspaceProperties.setBaseDir(rootDir.resolve("threads"));
        ThreadWorkspaceService threadWorkspaceService = new ThreadWorkspaceService(workspaceProperties);
        DocumentMarkdownConversionService documentMarkdownConversionService = new DocumentMarkdownConversionService();
        UploadService uploadService = new UploadService(threadWorkspaceService, documentMarkdownConversionService);
        ArtifactService artifactService = new ArtifactService(threadWorkspaceService);
        MemoryStoreProperties memoryStoreProperties = new MemoryStoreProperties();
        memoryStoreProperties.setBaseDir(rootDir.resolve("memory"));
        FileMemoryStore fileMemoryStore = new FileMemoryStore(memoryStoreProperties, new ObjectMapper());
        RuntimeGraphFactory runtimeGraphFactory = new RuntimeGraphFactory(
                threadWorkspaceService,
                uploadService,
                artifactService,
                new MemoryInjectionService(fileMemoryStore, new MemoryInjectionProperties())
        );
        LeadAgentFactory leadAgentFactory = new LeadAgentFactory();
        RuntimeAgentEnhancementService runtimeAgentEnhancementService =
                new RuntimeAgentEnhancementService(new ObjectMapper());
        RuntimeCheckpointProperties runtimeCheckpointProperties = new RuntimeCheckpointProperties();
        runtimeCheckpointProperties.setBaseDir(rootDir.resolve("checkpoints"));
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
                runtimeGraphFactory,
                leadAgentFactory,
                runtimeAgentEnhancementService,
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
        return new ThreadRuntimeServiceFixture(threadRuntimeService, runtimeGraphFactory, runtimeCheckpointService);
    }

    private Optional<OverAllState> runtimeState(ThreadRuntimeServiceFixture fixture, String threadId) {
        AsyncNodeActionWithConfig noopLeadAgentNode = (state, config) -> CompletableFuture.completedFuture(Map.of());
        Optional<StateSnapshot> stateSnapshot = fixture.runtimeGraphFactory
                .create(noopLeadAgentNode, fixture.runtimeCheckpointService.runtimeGraphSaver())
                .stateOf(RunnableConfig.builder().threadId(threadId).build());
        return stateSnapshot.map(StateSnapshot::state);
    }

    private record ThreadRuntimeServiceFixture(
            ThreadRuntimeService threadRuntimeService,
            RuntimeGraphFactory runtimeGraphFactory,
            RuntimeCheckpointService runtimeCheckpointService
    ) {
    }

    private static final class PromptCountingChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            long userMessages = messages.stream()
                    .filter(UserMessage.class::isInstance)
                    .count();
            long assistantMessages = messages.stream()
                    .filter(AssistantMessage.class::isInstance)
                    .count();

            String lastUserMessage = messages.stream()
                    .filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast)
                    .reduce((previous, current) -> current)
                    .map(UserMessage::getText)
                    .orElse("N/A");

            String content = "userMessages=%d; assistantMessages=%d; lastUser=%s"
                    .formatted(userMessages, assistantMessages, lastUserMessage);

            return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
        }
    }

    private static final class PlanningChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            boolean hasToolResponse = messages.stream().anyMatch(ToolResponseMessage.class::isInstance);

            if (!hasToolResponse) {
                AssistantMessage toolCall = AssistantMessage.builder()
                        .content("Planning work")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "todo-call-1",
                                "function",
                                "write_todos",
                                """
                                        {
                                          "todos": [
                                            {"content": "Read uploaded brief", "status": "IN_PROGRESS"},
                                            {"content": "Draft summary", "status": "PENDING"}
                                          ]
                                        }
                                        """
                        )))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCall)));
            }

            return new ChatResponse(List.of(new Generation(new AssistantMessage("planning-finished"))));
        }
    }
}
