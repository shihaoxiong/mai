package com.mai.deerflow.backend.runtime.api;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.mai.deerflow.backend.runtime.agent.LeadAgentDefinition;
import com.mai.deerflow.backend.runtime.agent.LeadAgentFactory;
import com.mai.deerflow.backend.runtime.agent.RuntimeAgentEnhancementService;
import com.mai.deerflow.backend.runtime.artifact.ArtifactService;
import com.mai.deerflow.backend.runtime.contract.ApprovalState;
import com.mai.deerflow.backend.runtime.contract.ApprovalStatus;
import com.mai.deerflow.backend.runtime.contract.ArtifactRef;
import com.mai.deerflow.backend.runtime.contract.RunStatus;
import com.mai.deerflow.backend.runtime.contract.ThreadStateSnapshot;
import com.mai.deerflow.backend.runtime.contract.TodoItem;
import com.mai.deerflow.backend.runtime.contract.UploadRef;
import com.mai.deerflow.backend.runtime.graph.RuntimeGraphFactory;
import com.mai.deerflow.backend.runtime.graph.RuntimeStateKeys;
import com.mai.deerflow.backend.runtime.state.RunStateMachine;
import com.mai.deerflow.backend.runtime.upload.UploadService;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspace;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class ThreadRuntimeService {

    private final ThreadWorkspaceService threadWorkspaceService;
    private final RuntimeGraphFactory runtimeGraphFactory;
    private final LeadAgentFactory leadAgentFactory;
    private final RuntimeAgentEnhancementService runtimeAgentEnhancementService;
    private final ChatModel chatModel;
    private final RunStateMachine runStateMachine;
    private final ObjectMapper objectMapper;
    private final UploadService uploadService;
    private final ArtifactService artifactService;
    private final ConcurrentMap<String, ThreadStateSnapshot> threadSnapshots = new ConcurrentHashMap<>();

    public ThreadRuntimeService(ThreadWorkspaceService threadWorkspaceService,
                                RuntimeGraphFactory runtimeGraphFactory,
                                LeadAgentFactory leadAgentFactory,
                                RuntimeAgentEnhancementService runtimeAgentEnhancementService,
                                ChatModel chatModel,
                                RunStateMachine runStateMachine,
                                ObjectMapper objectMapper,
                                UploadService uploadService,
                                ArtifactService artifactService) {
        this.threadWorkspaceService = threadWorkspaceService;
        this.runtimeGraphFactory = runtimeGraphFactory;
        this.leadAgentFactory = leadAgentFactory;
        this.runtimeAgentEnhancementService = runtimeAgentEnhancementService;
        this.chatModel = chatModel;
        this.runStateMachine = runStateMachine;
        this.objectMapper = objectMapper;
        this.uploadService = uploadService;
        this.artifactService = artifactService;
    }

    public ThreadStateSnapshot createThread(String requestedThreadId) {
        String threadId = requestedThreadId == null || requestedThreadId.isBlank()
                ? UUID.randomUUID().toString()
                : requestedThreadId.trim();

        ThreadWorkspace workspace = threadWorkspaceService.getOrCreateWorkspace(threadId);
        ThreadStateSnapshot snapshot = idleSnapshot(workspace);
        threadSnapshots.put(threadId, snapshot);
        persistSnapshot(snapshot);
        return snapshot;
    }

    public ThreadStateSnapshot getThread(String threadId) {
        ThreadStateSnapshot snapshot = threadSnapshots.get(threadId);
        if (snapshot != null) {
            ThreadStateSnapshot refreshedSnapshot = refreshThreadSnapshot(snapshot);
            threadSnapshots.put(threadId, refreshedSnapshot);
            persistSnapshot(refreshedSnapshot);
            return refreshedSnapshot;
        }
        ThreadStateSnapshot persistedSnapshot = loadSnapshot(threadId).orElse(null);
        if (persistedSnapshot != null) {
            ThreadStateSnapshot refreshedSnapshot = refreshThreadSnapshot(persistedSnapshot);
            threadSnapshots.put(threadId, refreshedSnapshot);
            persistSnapshot(refreshedSnapshot);
            return refreshedSnapshot;
        }
        if (!threadWorkspaceService.exists(threadId)) {
            throw new ThreadNotFoundException(threadId);
        }
        ThreadStateSnapshot recoveredIdleSnapshot = idleSnapshot(threadWorkspaceService.getWorkspace(threadId));
        threadSnapshots.put(threadId, recoveredIdleSnapshot);
        persistSnapshot(recoveredIdleSnapshot);
        return recoveredIdleSnapshot;
    }

    public ThreadStateSnapshot runThread(String threadId, String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }

        ThreadWorkspace workspace = threadWorkspaceService.getOrCreateWorkspace(threadId);
        ThreadStateSnapshot currentSnapshot = threadSnapshots.getOrDefault(threadId, idleSnapshot(workspace));
        String runId = UUID.randomUUID().toString();
        AsyncNodeActionWithConfig runLeadAgentNode = runLeadAgentNode();

        ThreadStateSnapshot runningSnapshot = withStatus(currentSnapshot, runId, RunStatus.RUNNING);
        threadSnapshots.put(threadId, runningSnapshot);
        persistSnapshot(runningSnapshot);

        try {
            Optional<OverAllState> result = runtimeGraphFactory.create(runLeadAgentNode).invoke(
                    Map.of(
                            RuntimeStateKeys.THREAD_ID, threadId,
                            RuntimeStateKeys.RUN_ID, runId,
                            RuntimeStateKeys.USER_INPUT, message
                    ),
                    RunnableConfig.builder().threadId(threadId).build()
            );

            OverAllState state = result.orElseThrow(() -> new IllegalStateException("Runtime graph returned no state"));
            ThreadStateSnapshot snapshot = new ThreadStateSnapshot(
                    threadId,
                    runId,
                    runStateMachine.transition(runningSnapshot.runStatus(), RunStatus.COMPLETED),
                    workspace.toState(),
                    currentUploads(threadId),
                    currentArtifacts(threadId),
                    extractTodosFromLeadState(state),
                    new ApprovalState(null, ApprovalStatus.NONE, null),
                    suggestionsFrom(state),
                    titleFrom(state, message)
            );

            threadSnapshots.put(threadId, snapshot);
            persistSnapshot(snapshot);
            return snapshot;
        }
        catch (RuntimeException exception) {
            ThreadStateSnapshot failedSnapshot = new ThreadStateSnapshot(
                    threadId,
                    runId,
                    runStateMachine.transition(runningSnapshot.runStatus(), RunStatus.FAILED),
                    workspace.toState(),
                    currentUploads(threadId),
                    List.of(),
                    List.of(),
                    new ApprovalState(null, ApprovalStatus.NONE, null),
                    List.of(),
                    deriveTitle(message)
            );
            threadSnapshots.put(threadId, failedSnapshot);
            persistSnapshot(failedSnapshot);
            throw exception;
        }
    }

    public void deleteThread(String threadId) {
        if (!threadWorkspaceService.exists(threadId) && !threadSnapshots.containsKey(threadId)) {
            throw new ThreadNotFoundException(threadId);
        }
        threadSnapshots.remove(threadId);
        threadWorkspaceService.deleteWorkspace(threadId);
    }

    private ThreadStateSnapshot idleSnapshot(ThreadWorkspace workspace) {
        return new ThreadStateSnapshot(
                workspace.threadId(),
                null,
                RunStatus.IDLE,
                workspace.toState(),
                currentUploads(workspace.threadId()),
                currentArtifacts(workspace.threadId()),
                List.of(),
                new ApprovalState(null, ApprovalStatus.NONE, null),
                List.of(),
                null
        );
    }

    private AsyncNodeActionWithConfig runLeadAgentNode() {
        var leadAgent = leadAgentFactory.create(LeadAgentDefinition.builder(chatModel)
                .name("runtime-lead-agent")
                .instruction("You are the Java DeerFlow backend lead agent.")
                .hooks(runtimeAgentEnhancementService.defaultHooks(chatModel))
                .interceptors(runtimeAgentEnhancementService.defaultInterceptors())
                .saver(new com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver())
                .build());

        return (state, config) -> {
            String userInput = state.value(RuntimeStateKeys.USER_INPUT, "");
            String agentThreadId = "%s:%s".formatted(
                    state.value(RuntimeStateKeys.THREAD_ID, String.class).orElse("runtime-lead"),
                    state.value(RuntimeStateKeys.RUN_ID, String.class).orElse("run")
            );
            try {
                RunnableConfig agentConfig = RunnableConfig.builder().threadId(agentThreadId).build();
                AssistantMessage assistantMessage = leadAgent.call(
                        userInput,
                        agentConfig
                );
                Map<String, Object> leadThreadState = leadAgent.getCompiledGraph()
                        .getState(agentConfig)
                        .state()
                        .data();

                Map<String, Object> updates = new HashMap<>();
                updates.put(RuntimeStateKeys.TITLE, deriveTitle(userInput));
                updates.put(RuntimeStateKeys.SUGGESTIONS, List.of("continue this thread"));
                updates.put("assistantOutput", assistantMessage.getText());
                updates.put("leadThreadState", leadThreadState);

                return CompletableFuture.completedFuture(updates);
            }
            catch (Exception exception) {
                CompletableFuture<Map<String, Object>> failed = new CompletableFuture<>();
                failed.completeExceptionally(exception);
                return failed;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private List<String> suggestionsFrom(OverAllState state) {
        Object suggestions = state.value(RuntimeStateKeys.SUGGESTIONS).orElse(List.of());
        if (suggestions instanceof List<?> suggestionList) {
            return suggestionList.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private String titleFrom(OverAllState state, String fallbackMessage) {
        return state.value(RuntimeStateKeys.TITLE, String.class)
                .orElseGet(() -> deriveTitle(fallbackMessage));
    }

    private String deriveTitle(String message) {
        String normalized = message.trim();
        return normalized.length() <= 48 ? normalized : normalized.substring(0, 48);
    }

    @SuppressWarnings("unchecked")
    private List<TodoItem> extractTodosFromLeadState(OverAllState state) {
        if (state == null) {
            return List.of();
        }
        Object leadThreadState = state.value("leadThreadState").orElse(Map.of());
        if (leadThreadState instanceof Map<?, ?> leadStateMap) {
            return runtimeAgentEnhancementService.extractTodos((Map<String, Object>) leadStateMap);
        }
        return List.of();
    }

    private ThreadStateSnapshot refreshThreadSnapshot(ThreadStateSnapshot snapshot) {
        return new ThreadStateSnapshot(
                snapshot.threadId(),
                snapshot.runId(),
                snapshot.runStatus(),
                snapshot.workspace(),
                currentUploads(snapshot.threadId()),
                currentArtifacts(snapshot.threadId()),
                snapshot.todos(),
                snapshot.approval(),
                snapshot.suggestions(),
                snapshot.title()
        );
    }

    private ThreadStateSnapshot withStatus(ThreadStateSnapshot snapshot, String runId, RunStatus targetStatus) {
        return new ThreadStateSnapshot(
                snapshot.threadId(),
                runId,
                runStateMachine.transition(snapshot.runStatus(), targetStatus),
                snapshot.workspace(),
                currentUploads(snapshot.threadId()),
                currentArtifacts(snapshot.threadId()),
                snapshot.todos(),
                snapshot.approval(),
                snapshot.suggestions(),
                snapshot.title()
        );
    }

    private List<UploadRef> currentUploads(String threadId) {
        return uploadService.listUploads(threadId);
    }

    private List<ArtifactRef> currentArtifacts(String threadId) {
        return artifactService.listArtifacts(threadId);
    }

    private void persistSnapshot(ThreadStateSnapshot snapshot) {
        ThreadWorkspace workspace = threadWorkspaceService.getOrCreateWorkspace(snapshot.threadId());
        Path snapshotFile = snapshotFile(workspace);

        try {
            Files.createDirectories(snapshotFile.getParent());
            objectMapper.writeValue(snapshotFile.toFile(), snapshot);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to persist thread snapshot for " + snapshot.threadId(), exception);
        }
    }

    private Optional<ThreadStateSnapshot> loadSnapshot(String threadId) {
        if (!threadWorkspaceService.exists(threadId)) {
            return Optional.empty();
        }

        Path snapshotFile = snapshotFile(threadWorkspaceService.getWorkspace(threadId));
        if (!Files.isRegularFile(snapshotFile)) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.readValue(snapshotFile.toFile(), ThreadStateSnapshot.class));
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to load thread snapshot for " + threadId, exception);
        }
    }

    private Path snapshotFile(ThreadWorkspace workspace) {
        return workspace.threadRoot().resolve("metadata").resolve("thread-state.json");
    }
}
