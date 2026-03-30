package com.mai.deerflow.backend.runtime.api;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mai.deerflow.backend.runtime.agent.LeadAgentFactory;
import com.mai.deerflow.backend.runtime.agent.RuntimeAgentEnhancementService;
import com.mai.deerflow.backend.runtime.agent.RuntimeLeadAgentPromptService;
import com.mai.deerflow.backend.runtime.artifact.ArtifactService;
import com.mai.deerflow.backend.runtime.checkpoint.RuntimeCheckpointProperties;
import com.mai.deerflow.backend.runtime.checkpoint.RuntimeCheckpointService;
import com.mai.deerflow.backend.runtime.config.FileRuntimeConfigRepository;
import com.mai.deerflow.backend.runtime.config.RuntimeConfigProperties;
import com.mai.deerflow.backend.runtime.contract.ApprovalStatus;
import com.mai.deerflow.backend.runtime.contract.RunEventType;
import com.mai.deerflow.backend.runtime.contract.RunStatus;
import com.mai.deerflow.backend.runtime.contract.ThreadMessage;
import com.mai.deerflow.backend.runtime.contract.ThreadStateSnapshot;
import com.mai.deerflow.backend.runtime.event.ThreadEventService;
import com.mai.deerflow.backend.runtime.graph.RuntimeStateKeys;
import com.mai.deerflow.backend.runtime.contract.TodoItem;
import com.mai.deerflow.backend.runtime.memory.FileMemoryStore;
import com.mai.deerflow.backend.runtime.memory.MemoryExtractorJob;
import com.mai.deerflow.backend.runtime.memory.MemoryInjectionProperties;
import com.mai.deerflow.backend.runtime.memory.MemoryInjectionService;
import com.mai.deerflow.backend.runtime.memory.MemoryStoreProperties;
import com.mai.deerflow.backend.runtime.postrun.PostRunGenerationService;
import com.mai.deerflow.backend.runtime.skill.SkillDescriptor;
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
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.test.StepVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

            Map<String, Object> checkpointState = runtimeState(fixture, threadId).orElseThrow();
            List<ThreadMessage> messages = fixture.runtimeAgentEnhancementService.extractMessages(checkpointState);
            assertThat(messages).hasSize(4);
            assertThat(messages.get(2).content()).isEqualTo("follow up question");
            assertThat(messages.get(3).content())
                    .contains("userMessages=2")
                    .contains("assistantMessages=1")
                    .contains("lastUser=follow up question");
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

        Map<String, Object> checkpointState = runtimeState(fixture, threadId).orElseThrow();
        List<ThreadMessage> messages = fixture.runtimeAgentEnhancementService.extractMessages(checkpointState);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).content()).isEqualTo("fresh again");
        assertThat(messages.get(1).content())
                .contains("userMessages=1")
                .contains("assistantMessages=0")
                .contains("lastUser=fresh again");

        fixture.threadRuntimeService.deleteThread(threadId);
    }

    @Test
    void shouldProjectWriteTodosToolResultIntoRuntimeCheckpointState() {
        ThreadRuntimeServiceFixture fixture = fixture(new PlanningChatModel(), tempDir.resolve("todos"));
        String threadId = "checkpoint-todos-" + UUID.randomUUID();

        try {
            fixture.threadRuntimeService.createThread(threadId);
            fixture.threadRuntimeService.runThread(threadId, "plan this work");

            Map<String, Object> checkpointState = runtimeState(fixture, threadId).orElseThrow();
            List<?> todos = (List<?>) checkpointState.getOrDefault(RuntimeStateKeys.TODOS, List.of());
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

    @Test
    void shouldInjectThreadScopedPromptContextIntoLeadAgent() throws Exception {
        ThreadRuntimeServiceFixture fixture = fixture(new PromptContextChatModel(), tempDir.resolve("prompt-context"));
        String threadId = "checkpoint-prompt-context-" + UUID.randomUUID();

        try {
            fixture.skillRegistryService.replaceSkills(List.of(
                    new SkillDescriptor("analysis", "Analysis", "General long-form analysis skill.", true)
            ));

            fixture.threadRuntimeService.createThread(threadId);
            Path uploadPath = fixture.threadWorkspaceService.getWorkspace(threadId).uploadsRoot().resolve("brief.md");
            Files.writeString(uploadPath, "# prompt context");

            ThreadStateSnapshot snapshot = fixture.threadRuntimeService.runThread(threadId, "use prompt context");

            assertThat(snapshot.messages()).isNotEmpty();
            assertThat(snapshot.messages().get(snapshot.messages().size() - 1).content())
                    .contains("skill=true")
                    .contains("upload=true")
                    .contains("workspace=true");
        }
        finally {
            fixture.threadRuntimeService.deleteThread(threadId);
        }
    }

    @Test
    void shouldPauseRunWhenLeadAgentRequestsClarification() {
        ThreadRuntimeServiceFixture fixture = fixture(new ClarificationChatModel(), tempDir.resolve("ask-clarification"));
        String threadId = "checkpoint-clarification-" + UUID.randomUUID();

        try {
            fixture.threadRuntimeService.createThread(threadId);
            ThreadStateSnapshot waitingSnapshot = fixture.threadRuntimeService.runThread(
                    threadId,
                    "clarify the deployment target before continuing"
            );

            assertThat(waitingSnapshot.runStatus()).isEqualTo(RunStatus.WAITING_CLARIFICATION);
            assertThat(waitingSnapshot.approval().status()).isEqualTo(ApprovalStatus.NEEDS_CLARIFICATION);
            assertThat(waitingSnapshot.approval().reason()).contains("Which environment should I use?");
            assertThat(waitingSnapshot.messages()).isNotEmpty();

            Map<String, Object> checkpointState = runtimeState(fixture, threadId).orElseThrow();
            Object pendingApproval = checkpointState.get(RuntimeStateKeys.PENDING_APPROVAL);
            assertThat(pendingApproval).isNotNull();

            StepVerifier.create(fixture.threadEventService.stream(threadId)
                            .take(2)
                            .map(event -> event.data().eventType()))
                    .expectNext(RunEventType.RUN_STARTED, RunEventType.APPROVAL_REQUIRED)
                    .verifyComplete();

            ThreadStateSnapshot resumedSnapshot = fixture.threadRuntimeService.resumeThread(
                    threadId,
                    new ResumeThreadRequest("Use staging for this rollout")
            );
            assertThat(resumedSnapshot.runStatus()).isEqualTo(RunStatus.COMPLETED);
            assertThat(resumedSnapshot.approval().status()).isEqualTo(ApprovalStatus.NEEDS_CLARIFICATION);
            assertThat(resumedSnapshot.approval().reason()).isEqualTo("Use staging for this rollout");
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
        RuntimeConfigProperties runtimeConfigProperties = new RuntimeConfigProperties();
        runtimeConfigProperties.setFile(rootDir.resolve("runtime-config.json"));
        SkillRegistryService skillRegistryService = new SkillRegistryService(
                new FileRuntimeConfigRepository(runtimeConfigProperties, new ObjectMapper())
        );
        MemoryStoreProperties memoryStoreProperties = new MemoryStoreProperties();
        memoryStoreProperties.setBaseDir(rootDir.resolve("memory"));
        FileMemoryStore fileMemoryStore = new FileMemoryStore(memoryStoreProperties, new ObjectMapper());
        MemoryInjectionService memoryInjectionService = new MemoryInjectionService(fileMemoryStore, new MemoryInjectionProperties());
        LeadAgentFactory leadAgentFactory = new LeadAgentFactory();
        RuntimeAgentEnhancementService runtimeAgentEnhancementService =
                new RuntimeAgentEnhancementService(new ObjectMapper());
        RuntimeLeadAgentPromptService runtimeLeadAgentPromptService = new RuntimeLeadAgentPromptService(
                threadWorkspaceService,
                uploadService,
                skillRegistryService
        );
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
        return new ThreadRuntimeServiceFixture(
                threadRuntimeService,
                runtimeCheckpointService,
                runtimeAgentEnhancementService,
                threadWorkspaceService,
                skillRegistryService,
                threadEventService
        );
    }

    private Optional<Map<String, Object>> runtimeState(ThreadRuntimeServiceFixture fixture, String threadId) {
        return fixture.runtimeCheckpointService.leadAgentSaver()
                .get(RunnableConfig.builder().threadId(threadId).build())
                .map(Checkpoint::getState);
    }

    private record ThreadRuntimeServiceFixture(
            ThreadRuntimeService threadRuntimeService,
            RuntimeCheckpointService runtimeCheckpointService,
            RuntimeAgentEnhancementService runtimeAgentEnhancementService,
            ThreadWorkspaceService threadWorkspaceService,
            SkillRegistryService skillRegistryService,
            ThreadEventService threadEventService
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

    private static final class PromptContextChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            String systemPrompt = messages.stream()
                    .filter(SystemMessage.class::isInstance)
                    .map(SystemMessage.class::cast)
                    .map(SystemMessage::getText)
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");

            String content = "skill=%s; upload=%s; workspace=%s".formatted(
                    systemPrompt.contains("Analysis [analysis]"),
                    systemPrompt.contains("/uploads/brief.md"),
                    systemPrompt.contains("/workspace")
            );
            return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
        }
    }

    private static final class ClarificationChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            String latestUserMessage = messages.stream()
                    .filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast)
                    .reduce((previous, current) -> current)
                    .map(UserMessage::getText)
                    .orElse("");

            if (!latestUserMessage.contains("Additional clarification from user:")) {
                AssistantMessage toolCallMessage = AssistantMessage.builder()
                        .content("I need clarification before I continue.")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-clarification",
                                "function",
                                "ask_clarification",
                                """
                                {"question":"Which environment should I use?","context":"The request mentions deployment, but the target is ambiguous.","options":["staging","production"]}
                                """
                        )))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCallMessage)));
            }

            return new ChatResponse(List.of(new Generation(
                    new AssistantMessage("clarification_applied=" + latestUserMessage)
            )));
        }
    }
}
