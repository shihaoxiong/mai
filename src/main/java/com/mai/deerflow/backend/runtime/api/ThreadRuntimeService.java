package com.mai.deerflow.backend.runtime.api;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.mai.deerflow.backend.runtime.agent.LeadAgentDefinition;
import com.mai.deerflow.backend.runtime.agent.LeadAgentFactory;
import com.mai.deerflow.backend.runtime.contract.ApprovalState;
import com.mai.deerflow.backend.runtime.contract.ApprovalStatus;
import com.mai.deerflow.backend.runtime.contract.ArtifactRef;
import com.mai.deerflow.backend.runtime.contract.RunStatus;
import com.mai.deerflow.backend.runtime.contract.ThreadStateSnapshot;
import com.mai.deerflow.backend.runtime.graph.RuntimeGraphFactory;
import com.mai.deerflow.backend.runtime.graph.RuntimeStateKeys;
import com.mai.deerflow.backend.runtime.state.RunStateMachine;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspace;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

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
    private final ChatModel chatModel;
    private final RunStateMachine runStateMachine;
    private final ConcurrentMap<String, ThreadStateSnapshot> threadSnapshots = new ConcurrentHashMap<>();

    public ThreadRuntimeService(ThreadWorkspaceService threadWorkspaceService,
                                RuntimeGraphFactory runtimeGraphFactory,
                                LeadAgentFactory leadAgentFactory,
                                ChatModel chatModel,
                                RunStateMachine runStateMachine) {
        this.threadWorkspaceService = threadWorkspaceService;
        this.runtimeGraphFactory = runtimeGraphFactory;
        this.leadAgentFactory = leadAgentFactory;
        this.chatModel = chatModel;
        this.runStateMachine = runStateMachine;
    }

    public ThreadStateSnapshot createThread(String requestedThreadId) {
        String threadId = requestedThreadId == null || requestedThreadId.isBlank()
                ? UUID.randomUUID().toString()
                : requestedThreadId.trim();

        ThreadWorkspace workspace = threadWorkspaceService.getOrCreateWorkspace(threadId);
        ThreadStateSnapshot snapshot = idleSnapshot(workspace);
        threadSnapshots.put(threadId, snapshot);
        return snapshot;
    }

    public ThreadStateSnapshot getThread(String threadId) {
        ThreadStateSnapshot snapshot = threadSnapshots.get(threadId);
        if (snapshot != null) {
            return snapshot;
        }
        if (!threadWorkspaceService.exists(threadId)) {
            throw new ThreadNotFoundException(threadId);
        }
        return idleSnapshot(threadWorkspaceService.getWorkspace(threadId));
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
                    List.of(),
                    artifactsFrom(state),
                    List.of(),
                    new ApprovalState(null, ApprovalStatus.NONE, null),
                    suggestionsFrom(state),
                    titleFrom(state, message)
            );

            threadSnapshots.put(threadId, snapshot);
            return snapshot;
        }
        catch (RuntimeException exception) {
            ThreadStateSnapshot failedSnapshot = new ThreadStateSnapshot(
                    threadId,
                    runId,
                    runStateMachine.transition(runningSnapshot.runStatus(), RunStatus.FAILED),
                    workspace.toState(),
                    List.of(),
                    List.of(),
                    List.of(),
                    new ApprovalState(null, ApprovalStatus.NONE, null),
                    List.of(),
                    deriveTitle(message)
            );
            threadSnapshots.put(threadId, failedSnapshot);
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
                List.of(),
                List.of(),
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
                .build());

        return (state, config) -> {
            String userInput = state.value(RuntimeStateKeys.USER_INPUT, "");
            try {
                AssistantMessage assistantMessage = leadAgent.call(userInput);

                return CompletableFuture.completedFuture(Map.of(
                        RuntimeStateKeys.TITLE, deriveTitle(userInput),
                        RuntimeStateKeys.SUGGESTIONS, List.of("continue this thread"),
                        "assistantOutput", assistantMessage.getText()
                ));
            }
            catch (Exception exception) {
                CompletableFuture<Map<String, Object>> failed = new CompletableFuture<>();
                failed.completeExceptionally(exception);
                return failed;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private List<ArtifactRef> artifactsFrom(OverAllState state) {
        Object artifacts = state.value(RuntimeStateKeys.ARTIFACTS).orElse(List.of());
        if (artifacts instanceof List<?> artifactList && artifactList.stream().allMatch(ArtifactRef.class::isInstance)) {
            return (List<ArtifactRef>) artifactList;
        }
        return List.of();
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

    private ThreadStateSnapshot withStatus(ThreadStateSnapshot snapshot, String runId, RunStatus targetStatus) {
        return new ThreadStateSnapshot(
                snapshot.threadId(),
                runId,
                runStateMachine.transition(snapshot.runStatus(), targetStatus),
                snapshot.workspace(),
                snapshot.uploads(),
                snapshot.artifacts(),
                snapshot.todos(),
                snapshot.approval(),
                snapshot.suggestions(),
                snapshot.title()
        );
    }
}
