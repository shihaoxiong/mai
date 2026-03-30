package com.mai.deerflow.backend.runtime.api;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mai.deerflow.backend.runtime.agent.LeadAgentFactory;
import com.mai.deerflow.backend.runtime.agent.RuntimeAgentEnhancementService;
import com.mai.deerflow.backend.runtime.agent.RuntimeDeferredToolService;
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
                    .contains("follow up question");
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
                .contains("fresh again");

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

    @Test
    void shouldInjectThreadDataAndUploadsIntoModelRequestWithoutPollutingThreadMessages() throws Exception {
        ThreadRuntimeServiceFixture fixture = fixture(new TurnContextAwareChatModel(), tempDir.resolve("turn-context"));
        String threadId = "checkpoint-turn-context-" + UUID.randomUUID();

        try {
            fixture.threadRuntimeService.createThread(threadId);
            Files.writeString(
                    fixture.threadWorkspaceService.getWorkspace(threadId).uploadsRoot().resolve("brief.md"),
                    "# runtime turn context"
            );

            ThreadStateSnapshot snapshot = fixture.threadRuntimeService.runThread(threadId, "use current uploads to continue");

            assertThat(snapshot.runStatus()).isEqualTo(RunStatus.COMPLETED);
            assertThat(snapshot.messages()).isNotEmpty();
            assertThat(snapshot.messages().get(0).content()).isEqualTo("use current uploads to continue");
            assertThat(snapshot.messages().get(snapshot.messages().size() - 1).content())
                    .contains("threadData=true")
                    .contains("uploadedFiles=true")
                    .contains("originalUserPreserved=true");
        }
        finally {
            fixture.threadRuntimeService.deleteThread(threadId);
        }
    }

    @Test
    void shouldAllowLeadAgentToViewImageFromThreadWorkspace() throws Exception {
        ThreadRuntimeServiceFixture fixture = fixture(new ViewImageChatModel(), tempDir.resolve("view-image"));
        String threadId = "checkpoint-view-image-" + UUID.randomUUID();

        try {
            fixture.threadRuntimeService.createThread(threadId);
            Files.write(
                    fixture.threadWorkspaceService.getWorkspace(threadId).uploadsRoot().resolve("diagram.png"),
                    java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jC3sAAAAASUVORK5CYII=")
            );

            ThreadStateSnapshot snapshot = fixture.threadRuntimeService.runThread(threadId, "inspect the uploaded diagram");

            assertThat(snapshot.runStatus()).isEqualTo(RunStatus.COMPLETED);
            assertThat(snapshot.messages().get(snapshot.messages().size() - 1).content())
                    .contains("imageInjected=true")
                    .contains("mediaCount=1")
                    .contains("/uploads/diagram.png");
        }
        finally {
            fixture.threadRuntimeService.deleteThread(threadId);
        }
    }

    @Test
    void shouldAllowLeadAgentToDiscoverDeferredToolsViaToolSearch() throws Exception {
        ThreadRuntimeServiceFixture fixture = fixture(new DeferredToolSearchChatModel(), tempDir.resolve("deferred-tools"));
        String threadId = "checkpoint-deferred-tools-" + UUID.randomUUID();

        try {
            fixture.threadRuntimeService.createThread(threadId);
            Files.writeString(
                    fixture.threadWorkspaceService.getWorkspace(threadId).uploadsRoot().resolve("notes.md"),
                    "# deferred tool search"
            );

            ThreadStateSnapshot snapshot = fixture.threadRuntimeService.runThread(threadId, "find the deferred file tools and use them");

            assertThat(snapshot.runStatus()).isEqualTo(RunStatus.COMPLETED);
            assertThat(snapshot.messages().get(snapshot.messages().size() - 1).content())
                    .contains("deferredSearch=true")
                    .contains("list_thread_files")
                    .contains("/uploads/notes.md");
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
        RuntimeDeferredToolService runtimeDeferredToolService = new RuntimeDeferredToolService(
                threadWorkspaceService,
                new ObjectMapper()
        );
        runtimeLeadAgentPromptService.setRuntimeDeferredToolService(runtimeDeferredToolService);
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
        threadRuntimeService.setRuntimeDeferredToolService(runtimeDeferredToolService);
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

    private static final class TurnContextAwareChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            String systemText = messages.stream()
                    .filter(SystemMessage.class::isInstance)
                    .map(SystemMessage.class::cast)
                    .map(SystemMessage::getText)
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
            String latestUserMessage = messages.stream()
                    .filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast)
                    .reduce((previous, current) -> current)
                    .map(UserMessage::getText)
                    .orElse("");

            String content = "threadData=%s; uploadedFiles=%s; originalUserPreserved=%s".formatted(
                    systemText.contains("<thread_data>"),
                    systemText.contains("<uploaded_files>") && systemText.contains("/uploads/brief.md"),
                    latestUserMessage.contains("use current uploads to continue")
            );
            return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
        }
    }

    private static final class ViewImageChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            List<ToolResponseMessage> toolResponses = messages.stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .toList();

            if (toolResponses.isEmpty()) {
                AssistantMessage toolCallMessage = AssistantMessage.builder()
                        .content("Load the image first")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "view-image-1",
                                "function",
                                "view_image",
                                "{\"path\":\"/uploads/diagram.png\"}"
                        )))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCallMessage)));
            }

            UserMessage injectedImageMessage = messages.stream()
                    .filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast)
                    .filter(message -> message.getText() != null && message.getText().contains("Here are the images you've viewed:"))
                    .findFirst()
                    .orElseThrow();

            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "imageInjected=true; mediaCount=%d; content=%s".formatted(
                            injectedImageMessage.getMedia().size(),
                            injectedImageMessage.getText()
                    )
            ))));
        }
    }

    private static final class DeferredToolSearchChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            List<ToolResponseMessage> toolResponses = messages.stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .toList();

            if (toolResponses.isEmpty()) {
                AssistantMessage toolCallMessage = AssistantMessage.builder()
                        .content("Search deferred tools first")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "tool-search-1",
                                "function",
                                "tool_search",
                                "{\"query\":\"select:list_thread_files\"}"
                        )))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCallMessage)));
            }

            boolean hasToolSearchResponse = toolResponses.stream()
                    .flatMap(response -> response.getResponses().stream())
                    .anyMatch(response -> "tool_search".equals(response.name()));
            boolean hasListFilesResponse = toolResponses.stream()
                    .flatMap(response -> response.getResponses().stream())
                    .anyMatch(response -> "list_thread_files".equals(response.name()));

            if (hasToolSearchResponse && !hasListFilesResponse) {
                AssistantMessage toolCallMessage = AssistantMessage.builder()
                        .content("Now use the deferred tool")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "list-files-1",
                                "function",
                                "list_thread_files",
                                "{\"area\":\"uploads\"}"
                        )))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCallMessage)));
            }

            String toolSearchPayload = toolResponses.stream()
                    .flatMap(response -> response.getResponses().stream())
                    .filter(response -> "tool_search".equals(response.name()))
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .findFirst()
                    .orElse("");
            String listFilesPayload = toolResponses.stream()
                    .flatMap(response -> response.getResponses().stream())
                    .filter(response -> "list_thread_files".equals(response.name()))
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .findFirst()
                    .orElse("");

            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "deferredSearch=true; schema=%s; files=%s".formatted(toolSearchPayload, listFilesPayload)
            ))));
        }
    }
}
