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
import com.mai.deerflow.backend.runtime.postrun.PostRunGenerationService;
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
    void shouldAllowLeadAgentToDelegateSequentialSubTaskThroughTaskTool() {
        ThreadRuntimeServiceFixture fixture = fixture(new DelegatingChatModel(
                "sequential",
                List.of(
                        new StepPlan("research", "research data", "research_result"),
                        new StepPlan("review", "review findings", "review_result")
                )
        ));

        String threadId = "subtask-thread-" + UUID.randomUUID();
        try {
            fixture.threadRuntimeService.createThread(threadId);
            ThreadStateSnapshot snapshot = fixture.threadRuntimeService.runThread(
                    threadId,
                    "delegate this work to a child task",
                    false,
                    null
            );

            assertThat(snapshot.runStatus()).isEqualTo(RunStatus.COMPLETED);
            assertThat(fixture.subTaskExecutor.list(threadId))
                    .singleElement()
                    .satisfies(record -> {
                        assertThat(record.status()).isEqualTo(SubTaskStatus.COMPLETED);
                        assertThat(record.result()).contains("review_result");
                    });

            StepVerifier.create(fixture.threadEventService.stream(threadId)
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
            fixture.threadRuntimeService.deleteThread(threadId);
        }
    }

    @Test
    void shouldAllowLeadAgentToDelegateParallelSubTaskThroughTaskTool() {
        ThreadRuntimeServiceFixture fixture = fixture(new DelegatingChatModel(
                "parallel",
                List.of(
                        new StepPlan("collect", "collect bullets", "collect_result"),
                        new StepPlan("risk", "extract risks", "risk_result")
                )
        ));

        String threadId = "subtask-thread-" + UUID.randomUUID();
        try {
            fixture.threadRuntimeService.createThread(threadId);
            ThreadStateSnapshot snapshot = fixture.threadRuntimeService.runThread(
                    threadId,
                    "delegate this work to a child task",
                    false,
                    null
            );

            assertThat(snapshot.runStatus()).isEqualTo(RunStatus.COMPLETED);
            assertThat(fixture.subTaskExecutor.list(threadId))
                    .singleElement()
                    .satisfies(record -> {
                        assertThat(record.status()).isEqualTo(SubTaskStatus.COMPLETED);
                        assertThat(record.result()).contains("collect_result").contains("risk_result");
                    });

            StepVerifier.create(fixture.threadEventService.stream(threadId)
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
            fixture.threadRuntimeService.deleteThread(threadId);
        }
    }

    private ThreadRuntimeServiceFixture fixture(ChatModel chatModel) {
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
                subTaskExecutor,
                new PostRunGenerationService()
        );
        return new ThreadRuntimeServiceFixture(threadRuntimeService, subTaskExecutor, threadEventService);
    }

    private record ThreadRuntimeServiceFixture(
            ThreadRuntimeService threadRuntimeService,
            SubTaskExecutor subTaskExecutor,
            ThreadEventService threadEventService
    ) {
    }

    private record StepPlan(String name, String prompt, String result) {
    }

    private static final class DelegatingChatModel implements ChatModel {

        private final String mode;
        private final List<StepPlan> stepPlans;

        private DelegatingChatModel(String mode, List<StepPlan> stepPlans) {
            this.mode = mode;
            this.stepPlans = stepPlans;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            String joinedPrompt = messages.stream()
                    .map(Message::getText)
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
            String latestUserMessage = messages.stream()
                    .filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast)
                    .reduce((previous, current) -> current)
                    .map(UserMessage::getText)
                    .orElse("");

            if (latestUserMessage.startsWith("Delegated subtask") && joinedPrompt.contains("Step instruction:")) {
                for (int index = stepPlans.size() - 1; index >= 0; index--) {
                    StepPlan stepPlan = stepPlans.get(index);
                    if (joinedPrompt.contains("Step instruction: " + stepPlan.prompt())) {
                        return new ChatResponse(List.of(new Generation(
                                new AssistantMessage(stepPlan.result())
                        )));
                    }
                }
            }

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
                                buildToolArguments()
                        )))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCallMessage)));
            }

            String toolResult = toolResponses.get(0).getResponses().get(0).responseData();
            return new ChatResponse(List.of(new Generation(new AssistantMessage("parent_observation=" + toolResult))));
        }

        private String buildToolArguments() {
            String stepsJson = stepPlans.stream()
                    .map(stepPlan -> """
                            {"name":"%s","prompt":"%s"}
                            """.formatted(stepPlan.name(), stepPlan.prompt()))
                    .reduce((left, right) -> left + "," + right)
                    .orElse("");
            return """
                    {"action":"submit","title":"delegate work","prompt":"Coordinate delegated work and aggregate the result.","mode":"%s","steps":[%s],"waitForCompletion":true}
                    """.formatted(mode, stepsJson);
        }
    }
}
