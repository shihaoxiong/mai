package com.mai.deerflow.backend.runtime.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mai.deerflow.backend.runtime.agent.LeadAgentFactory;
import com.mai.deerflow.backend.runtime.agent.RuntimeAgentEnhancementService;
import com.mai.deerflow.backend.runtime.artifact.ArtifactService;
import com.mai.deerflow.backend.runtime.contract.RunEventType;
import com.mai.deerflow.backend.runtime.contract.RunStatus;
import com.mai.deerflow.backend.runtime.contract.ThreadStateSnapshot;
import com.mai.deerflow.backend.runtime.event.ThreadEventService;
import com.mai.deerflow.backend.runtime.graph.RuntimeGraphFactory;
import com.mai.deerflow.backend.runtime.memory.FileMemoryStore;
import com.mai.deerflow.backend.runtime.memory.MemoryExtractorJob;
import com.mai.deerflow.backend.runtime.memory.MemoryInjectionProperties;
import com.mai.deerflow.backend.runtime.memory.MemoryInjectionService;
import com.mai.deerflow.backend.runtime.memory.MemoryStoreProperties;
import com.mai.deerflow.backend.runtime.state.RunStateMachine;
import com.mai.deerflow.backend.runtime.subtask.SubTaskExecutor;
import com.mai.deerflow.backend.runtime.subtask.SubTaskRecord;
import com.mai.deerflow.backend.runtime.subtask.SubTaskStatus;
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
import reactor.test.StepVerifier;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ThreadRuntimeServiceSubTaskTests {

    @TempDir
    Path tempDir;

    @Test
    void shouldAllowLeadAgentToDelegateThroughTaskTool() {
        ThreadWorkspaceProperties workspaceProperties = new ThreadWorkspaceProperties();
        workspaceProperties.setBaseDir(tempDir.resolve("threads"));
        ThreadWorkspaceService threadWorkspaceService = new ThreadWorkspaceService(workspaceProperties);
        DocumentMarkdownConversionService documentMarkdownConversionService = new DocumentMarkdownConversionService();
        UploadService uploadService = new UploadService(threadWorkspaceService, documentMarkdownConversionService);
        ArtifactService artifactService = new ArtifactService(threadWorkspaceService);
        MemoryStoreProperties memoryStoreProperties = new MemoryStoreProperties();
        memoryStoreProperties.setBaseDir(tempDir.resolve("memory"));
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
        ChatModel chatModel = new SubTaskDelegatingChatModel();
        RunStateMachine runStateMachine = new RunStateMachine();
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
                runStateMachine,
                new ObjectMapper(),
                uploadService,
                artifactService,
                threadEventService,
                memoryExtractorJob,
                subTaskExecutor
        );

        String threadId = "subtask-thread-" + UUID.randomUUID();
        try {
            threadRuntimeService.createThread(threadId);
            ThreadStateSnapshot snapshot = threadRuntimeService.runThread(
                    threadId,
                    "delegate this work to a child task",
                    false,
                    null
            );

            assertThat(snapshot.runStatus()).isEqualTo(RunStatus.COMPLETED);
            assertThat(subTaskExecutor.list(threadId))
                    .singleElement()
                    .satisfies(record -> {
                        assertThat(record.status()).isEqualTo(SubTaskStatus.COMPLETED);
                        assertThat(record.result()).contains("subtask_result=");
                    });

            StepVerifier.create(threadEventService.stream(threadId)
                            .take(4)
                            .map(event -> event.data().eventType()))
                    .expectNext(
                            RunEventType.RUN_STARTED,
                            RunEventType.SUBTASK_STARTED,
                            RunEventType.SUBTASK_UPDATED,
                            RunEventType.RUN_COMPLETED
                    )
                    .verifyComplete();
        }
        finally {
            threadRuntimeService.deleteThread(threadId);
        }
    }

    private static final class SubTaskDelegatingChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            String latestUserMessage = messages.stream()
                    .filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast)
                    .reduce((previous, current) -> current)
                    .map(UserMessage::getText)
                    .orElse("");

            if (latestUserMessage.startsWith("Delegated subtask")) {
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage("subtask_result=" + latestUserMessage.substring(0, Math.min(48, latestUserMessage.length())))
                )));
            }

            List<ToolResponseMessage> toolResponses = messages.stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .toList();

            if (toolResponses.isEmpty()) {
                AssistantMessage toolCallMessage = AssistantMessage.builder()
                        .content("Delegating to task tool")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-task",
                                "function",
                                "task",
                                """
                                {"action":"submit","title":"delegate work","prompt":"Review delegated work and return a concise finding.","waitForCompletion":true}
                                """
                        )))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCallMessage)));
            }

            String toolResult = toolResponses.get(0).getResponses().get(0).responseData();
            return new ChatResponse(List.of(new Generation(new AssistantMessage("parent_observation=" + toolResult))));
        }
    }
}
