package com.mai.deerflow.backend.runtime.api;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mai.deerflow.backend.runtime.agent.LeadAgentDefinition;
import com.mai.deerflow.backend.runtime.agent.LeadAgentFactory;
import com.mai.deerflow.backend.runtime.agent.RuntimeAgentEnhancementService;
import com.mai.deerflow.backend.runtime.artifact.ArtifactService;
import com.mai.deerflow.backend.runtime.checkpoint.RuntimeCheckpointService;
import com.mai.deerflow.backend.runtime.contract.ApprovalState;
import com.mai.deerflow.backend.runtime.contract.ApprovalStatus;
import com.mai.deerflow.backend.runtime.contract.ArtifactRef;
import com.mai.deerflow.backend.runtime.contract.RunEventType;
import com.mai.deerflow.backend.runtime.contract.RunStatus;
import com.mai.deerflow.backend.runtime.contract.ThreadMessage;
import com.mai.deerflow.backend.runtime.contract.ThreadStateSnapshot;
import com.mai.deerflow.backend.runtime.contract.TodoItem;
import com.mai.deerflow.backend.runtime.contract.UploadRef;
import com.mai.deerflow.backend.runtime.event.ThreadEventService;
import com.mai.deerflow.backend.runtime.graph.RuntimeGraphFactory;
import com.mai.deerflow.backend.runtime.graph.RuntimeStateKeys;
import com.mai.deerflow.backend.runtime.memory.MemoryExtractionRequest;
import com.mai.deerflow.backend.runtime.memory.MemoryExtractorJob;
import com.mai.deerflow.backend.runtime.postrun.PostRunGenerationResult;
import com.mai.deerflow.backend.runtime.postrun.PostRunGenerationService;
import com.mai.deerflow.backend.runtime.state.RunStateMachine;
import com.mai.deerflow.backend.runtime.subtask.SubTaskExecutor;
import com.mai.deerflow.backend.runtime.subtask.SubTaskRecord;
import com.mai.deerflow.backend.runtime.upload.UploadService;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspace;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
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
/**
 * 线程运行时的核心编排服务。
 *
 * 负责把线程工作区、runtime graph、lead agent、审批恢复和事件流串成一条完整主链路。
 */
public class ThreadRuntimeService {

    private static final ApprovalState NO_APPROVAL = new ApprovalState(null, ApprovalStatus.NONE, null);
    private static final Logger logger = LoggerFactory.getLogger(ThreadRuntimeService.class);

    private final ThreadWorkspaceService threadWorkspaceService;
    private final RuntimeGraphFactory runtimeGraphFactory;
    private final LeadAgentFactory leadAgentFactory;
    private final RuntimeAgentEnhancementService runtimeAgentEnhancementService;
    private final ChatModel chatModel;
    private final RunStateMachine runStateMachine;
    private final ObjectMapper objectMapper;
    private final UploadService uploadService;
    private final ArtifactService artifactService;
    private final ThreadEventService threadEventService;
    private final MemoryExtractorJob memoryExtractorJob;
    private final SubTaskExecutor subTaskExecutor;
    private final PostRunGenerationService postRunGenerationService;
    private final RuntimeCheckpointService runtimeCheckpointService;
    private final ConcurrentMap<String, ThreadStateSnapshot> threadSnapshots = new ConcurrentHashMap<>();

    public ThreadRuntimeService(ThreadWorkspaceService threadWorkspaceService,
                                RuntimeGraphFactory runtimeGraphFactory,
                                LeadAgentFactory leadAgentFactory,
                                RuntimeAgentEnhancementService runtimeAgentEnhancementService,
                                @Qualifier("runtimeChatModel") ChatModel chatModel,
                                RunStateMachine runStateMachine,
                                ObjectMapper objectMapper,
                                UploadService uploadService,
                                ArtifactService artifactService,
                                ThreadEventService threadEventService,
                                MemoryExtractorJob memoryExtractorJob,
                                SubTaskExecutor subTaskExecutor,
                                PostRunGenerationService postRunGenerationService,
                                RuntimeCheckpointService runtimeCheckpointService) {
        this.threadWorkspaceService = threadWorkspaceService;
        this.runtimeGraphFactory = runtimeGraphFactory;
        this.leadAgentFactory = leadAgentFactory;
        this.runtimeAgentEnhancementService = runtimeAgentEnhancementService;
        this.chatModel = chatModel;
        this.runStateMachine = runStateMachine;
        this.objectMapper = objectMapper;
        this.uploadService = uploadService;
        this.artifactService = artifactService;
        this.threadEventService = threadEventService;
        this.memoryExtractorJob = memoryExtractorJob;
        this.subTaskExecutor = subTaskExecutor;
        this.postRunGenerationService = postRunGenerationService;
        this.runtimeCheckpointService = runtimeCheckpointService;
    }

    /**
     * 创建线程并初始化内存态与 checkpoint。
     */
    public ThreadStateSnapshot createThread(String requestedThreadId) {
        String threadId = requestedThreadId == null || requestedThreadId.isBlank()
                ? UUID.randomUUID().toString()
                : requestedThreadId.trim();

        ThreadWorkspace workspace = threadWorkspaceService.getOrCreateWorkspace(threadId);
        ThreadStateSnapshot snapshot = idleSnapshot(workspace);
        threadSnapshots.put(threadId, snapshot);
        persistRuntimeThreadState(snapshot);
        return snapshot;
    }

    /**
     * 获取线程当前状态；若内存中不存在，则尝试从 checkpoint 恢复。
     */
    public ThreadStateSnapshot getThread(String threadId) {
        ThreadStateSnapshot snapshot = threadSnapshots.get(threadId);
        if (snapshot != null) {
            ThreadStateSnapshot refreshedSnapshot = refreshThreadSnapshot(snapshot);
            threadSnapshots.put(threadId, refreshedSnapshot);
            return refreshedSnapshot;
        }

        ThreadStateSnapshot checkpointSnapshot = loadSnapshotFromCheckpoint(threadId).orElse(null);
        if (checkpointSnapshot != null) {
            ThreadStateSnapshot refreshedSnapshot = refreshThreadSnapshot(checkpointSnapshot);
            threadSnapshots.put(threadId, refreshedSnapshot);
            return refreshedSnapshot;
        }

        ThreadStateSnapshot pendingApprovalSnapshot = loadSnapshotFromPendingApproval(threadId).orElse(null);
        if (pendingApprovalSnapshot != null) {
            ThreadStateSnapshot refreshedSnapshot = refreshThreadSnapshot(pendingApprovalSnapshot);
            threadSnapshots.put(threadId, refreshedSnapshot);
            return refreshedSnapshot;
        }

        if (!threadWorkspaceService.exists(threadId)) {
            throw new ThreadNotFoundException(threadId);
        }

        ThreadStateSnapshot recoveredIdleSnapshot = idleSnapshot(threadWorkspaceService.getWorkspace(threadId));
        threadSnapshots.put(threadId, recoveredIdleSnapshot);
        return recoveredIdleSnapshot;
    }

    /**
     * 以默认无需审批的方式执行一次线程运行。
     */
    public ThreadStateSnapshot runThread(String threadId, String message) {
        return runThread(threadId, message, false, null);
    }

    /**
     * 执行一次线程运行，并支持在进入真正执行前挂起审批。
     */
    public ThreadStateSnapshot runThread(String threadId,
                                         String message,
                                         boolean approvalRequired,
                                         String approvalReason) {
        return runThread(threadId, message, approvalRequired, approvalReason, null);
    }

    /**
     * 执行一次线程运行，并按需把线程绑定到某个 userId，供长期记忆抽取使用。
     */
    public ThreadStateSnapshot runThread(String threadId,
                                         String message,
                                         boolean approvalRequired,
                                         String approvalReason,
                                         String userId) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }

        ThreadWorkspace workspace = threadWorkspaceService.getOrCreateWorkspace(threadId);
        ThreadStateSnapshot currentSnapshot = currentSnapshot(threadId, workspace);
        ThreadContextMetadata threadContext = resolveThreadContext(threadId, userId);
        String runId = UUID.randomUUID().toString();

        if (approvalRequired) {
            return createPendingApproval(threadId, runId, message, approvalReason, currentSnapshot, workspace);
        }

        return executeRun(threadId, message, runId, currentSnapshot, NO_APPROVAL, threadContext);
    }

    /**
     * 提交审批结果。
     */
    public ThreadStateSnapshot submitApproval(String threadId, String approvalId, ApprovalSubmissionRequest request) {
        PendingApproval pendingApproval = loadPendingApproval(threadId)
                .orElseThrow(() -> new ApprovalOperationException("No pending approval exists for thread " + threadId));

        if (!pendingApproval.approvalId().equals(approvalId)) {
            throw new ApprovalOperationException("Approval id does not match pending approval for thread " + threadId);
        }

        ApprovalDecision decision = request == null ? null : request.decision();
        if (decision == null) {
            throw new ApprovalOperationException("Approval decision must not be null");
        }

        ThreadStateSnapshot currentSnapshot = getThread(threadId);

        if (decision == ApprovalDecision.REJECT) {
            deletePendingApproval(threadId);

            ThreadStateSnapshot rejectedSnapshot = new ThreadStateSnapshot(
                    threadId,
                    pendingApproval.runId(),
                    runStateMachine.transition(currentSnapshot.runStatus(), RunStatus.FAILED),
                    currentSnapshot.workspace(),
                    currentUploads(threadId),
                    currentArtifacts(threadId),
                    currentSnapshot.messages(),
                    currentSnapshot.todos(),
                    new ApprovalState(approvalId, ApprovalStatus.REJECTED, request.comment()),
                    currentSnapshot.suggestions(),
                    currentSnapshot.title()
            );
            threadSnapshots.put(threadId, rejectedSnapshot);
            persistRuntimeThreadState(rejectedSnapshot);
            threadEventService.emit(threadId, pendingApproval.runId(), RunEventType.RUN_FAILED, rejectedSnapshot.approval());
            return rejectedSnapshot;
        }

        if (decision == ApprovalDecision.REQUEST_CLARIFICATION) {
            String clarificationRequest = request.comment() == null || request.comment().isBlank()
                    ? "Additional clarification is required before execution can continue"
                    : request.comment().trim();

            PendingApproval clarificationApproval = new PendingApproval(
                    pendingApproval.threadId(),
                    pendingApproval.runId(),
                    pendingApproval.approvalId(),
                    pendingApproval.message(),
                    pendingApproval.reason(),
                    ApprovalStatus.NEEDS_CLARIFICATION,
                    clarificationRequest
            );
            persistPendingApproval(clarificationApproval);

            ThreadStateSnapshot clarificationSnapshot = new ThreadStateSnapshot(
                    threadId,
                    pendingApproval.runId(),
                    runStateMachine.transition(currentSnapshot.runStatus(), RunStatus.WAITING_CLARIFICATION),
                    currentSnapshot.workspace(),
                    currentUploads(threadId),
                    currentArtifacts(threadId),
                    currentSnapshot.messages(),
                    currentSnapshot.todos(),
                    new ApprovalState(approvalId, ApprovalStatus.NEEDS_CLARIFICATION, clarificationRequest),
                    currentSnapshot.suggestions(),
                    currentSnapshot.title()
            );
            threadSnapshots.put(threadId, clarificationSnapshot);
            persistRuntimeThreadState(clarificationSnapshot);
            threadEventService.emit(threadId, pendingApproval.runId(), RunEventType.APPROVAL_REQUIRED, clarificationSnapshot.approval());
            return clarificationSnapshot;
        }

        PendingApproval approvedApproval = new PendingApproval(
                pendingApproval.threadId(),
                pendingApproval.runId(),
                pendingApproval.approvalId(),
                pendingApproval.message(),
                pendingApproval.reason(),
                ApprovalStatus.APPROVED,
                request.comment()
        );
        persistPendingApproval(approvedApproval);

        ThreadStateSnapshot approvedSnapshot = new ThreadStateSnapshot(
                threadId,
                pendingApproval.runId(),
                currentSnapshot.runStatus(),
                currentSnapshot.workspace(),
                currentUploads(threadId),
                currentArtifacts(threadId),
                currentSnapshot.messages(),
                currentSnapshot.todos(),
                new ApprovalState(approvalId, ApprovalStatus.APPROVED, request.comment()),
                currentSnapshot.suggestions(),
                currentSnapshot.title()
        );
        threadSnapshots.put(threadId, approvedSnapshot);
        persistRuntimeThreadState(approvedSnapshot);
        return approvedSnapshot;
    }

    /**
     * 在审批已通过的前提下恢复线程执行。
     */
    public ThreadStateSnapshot resumeThread(String threadId, ResumeThreadRequest request) {
        PendingApproval pendingApproval = loadPendingApproval(threadId)
                .orElseThrow(() -> new ApprovalOperationException("No pending approval exists for thread " + threadId));

        if (pendingApproval.status() != ApprovalStatus.APPROVED
                && pendingApproval.status() != ApprovalStatus.NEEDS_CLARIFICATION) {
            throw new ApprovalOperationException("Pending approval must be approved or clarified before resume");
        }

        String resumedMessage = pendingApproval.message();
        ApprovalState resumeApprovalState;
        if (pendingApproval.status() == ApprovalStatus.NEEDS_CLARIFICATION) {
            String clarificationComment = request == null || request.comment() == null || request.comment().isBlank()
                    ? null
                    : request.comment().trim();
            if (clarificationComment == null) {
                throw new ApprovalOperationException("Clarification comment must not be blank when resuming a clarification request");
            }
            resumedMessage = withClarification(pendingApproval.message(), clarificationComment);
            resumeApprovalState = new ApprovalState(
                    pendingApproval.approvalId(),
                    ApprovalStatus.NEEDS_CLARIFICATION,
                    clarificationComment
            );
        }
        else {
            resumeApprovalState = new ApprovalState(
                    pendingApproval.approvalId(),
                    ApprovalStatus.APPROVED,
                    pendingApproval.comment()
            );
        }

        ThreadStateSnapshot currentSnapshot = getThread(threadId);
        ThreadStateSnapshot resumedSnapshot = executeRun(
                threadId,
                resumedMessage,
                pendingApproval.runId(),
                currentSnapshot,
                resumeApprovalState,
                resolveThreadContext(threadId, null)
        );
        deletePendingApproval(threadId);
        return resumedSnapshot;
    }

    /**
     * 删除线程及其工作区，同时清理待审批文件。
     */
    public void deleteThread(String threadId) {
        if (!threadWorkspaceService.exists(threadId) && !threadSnapshots.containsKey(threadId)) {
            throw new ThreadNotFoundException(threadId);
        }
        threadSnapshots.remove(threadId);
        deletePendingApproval(threadId);
        runtimeCheckpointService.deleteThreadCheckpoints(threadId);
        threadWorkspaceService.deleteWorkspace(threadId);
    }

    private ThreadStateSnapshot createPendingApproval(String threadId,
                                                      String runId,
                                                      String message,
                                                      String approvalReason,
                                                      ThreadStateSnapshot currentSnapshot,
                                                      ThreadWorkspace workspace) {
        String approvalId = UUID.randomUUID().toString();
        String reason = approvalReason == null || approvalReason.isBlank()
                ? "Manual approval required"
                : approvalReason;

        PendingApproval pendingApproval = new PendingApproval(
                threadId,
                runId,
                approvalId,
                message,
                reason,
                ApprovalStatus.WAITING,
                null
        );

        ThreadStateSnapshot waitingSnapshot = new ThreadStateSnapshot(
                threadId,
                runId,
                runStateMachine.transition(currentSnapshot.runStatus(), RunStatus.WAITING_APPROVAL),
                workspace.toState(),
                currentUploads(threadId),
                currentArtifacts(threadId),
                currentSnapshot.messages(),
                currentSnapshot.todos(),
                new ApprovalState(approvalId, ApprovalStatus.WAITING, reason),
                currentSnapshot.suggestions(),
                currentSnapshot.title()
        );

        threadSnapshots.put(threadId, waitingSnapshot);
        persistRuntimeThreadState(waitingSnapshot);
        persistPendingApproval(pendingApproval);
        threadEventService.emit(threadId, runId, RunEventType.APPROVAL_REQUIRED, waitingSnapshot.approval());
        return waitingSnapshot;
    }

    /**
     * 执行真正的 runtime graph 主链路。
     */
    private ThreadStateSnapshot executeRun(String threadId,
                                           String message,
                                           String runId,
                                           ThreadStateSnapshot currentSnapshot,
                                           ApprovalState approvalState,
                                           ThreadContextMetadata threadContext) {
        ThreadWorkspace workspace = threadWorkspaceService.getOrCreateWorkspace(threadId);
        AsyncNodeActionWithConfig runLeadAgentNode = runLeadAgentNode();
        RunnableConfig runtimeConfig = RunnableConfig.builder().threadId(threadId).build();
        CompiledGraph runtimeGraph = runtimeGraphFactory.create(runLeadAgentNode, runtimeCheckpointService.runtimeGraphSaver());

        ThreadStateSnapshot runningSnapshot = withStatus(currentSnapshot, runId, RunStatus.RUNNING, approvalState);
        threadSnapshots.put(threadId, runningSnapshot);
        persistRuntimeThreadState(runningSnapshot);
        threadEventService.emit(threadId, runId, RunEventType.RUN_STARTED, Map.of("status", RunStatus.RUNNING.name()));

        try {
            Map<String, Object> initialState = new HashMap<>();
            initialState.put(RuntimeStateKeys.THREAD_ID, threadId);
            initialState.put(RuntimeStateKeys.RUN_ID, runId);
            initialState.put(RuntimeStateKeys.USER_INPUT, message);
            String userId = normalizeOptionalUserId(threadContext == null ? null : threadContext.userId());
            if (userId != null) {
                initialState.put(RuntimeStateKeys.USER_ID, userId);
            }

            Optional<OverAllState> result = runtimeGraph.invoke(initialState, runtimeConfig);

            OverAllState state = result.orElseThrow(() -> new IllegalStateException("Runtime graph returned no state"));
            List<UploadRef> uploads = currentUploads(threadId);
            List<ArtifactRef> artifacts = currentArtifacts(threadId);
            List<ThreadMessage> messages = messagesFromState(state);
            List<TodoItem> todos = todosFromState(state);
            List<SubTaskRecord> subTasks = currentSubTasks(threadId);
            PostRunGenerationResult postRunGenerationResult = postRunGenerationService.generate(
                    message,
                    assistantOutputFrom(state),
                    todos,
                    uploads,
                    artifacts,
                    subTasks
            );
            ThreadStateSnapshot snapshot = new ThreadStateSnapshot(
                    threadId,
                    runId,
                    runStateMachine.transition(runningSnapshot.runStatus(), RunStatus.COMPLETED),
                    workspace.toState(),
                    uploads,
                    artifacts,
                    messages,
                    todos,
                    approvalState,
                    suggestionsFrom(postRunGenerationResult, state),
                    titleFrom(postRunGenerationResult, state, message)
            );

            persistRuntimeCheckpointState(runtimeGraph, runtimeConfig, snapshot);
            threadSnapshots.put(threadId, snapshot);
            scheduleMemoryExtraction(threadContext, state, snapshot, message);
            threadEventService.emit(threadId, runId, RunEventType.RUN_COMPLETED, snapshot);
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
                    currentSnapshot.messages(),
                    List.of(),
                    approvalState,
                    List.of(),
                    deriveTitle(message)
            );
            threadSnapshots.put(threadId, failedSnapshot);
            persistRuntimeThreadState(failedSnapshot);
            threadEventService.emit(threadId, runId, RunEventType.RUN_FAILED, Map.of("message", String.valueOf(exception.getMessage())));
            throw exception;
        }
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
                List.of(),
                NO_APPROVAL,
                List.of(),
                null
        );
    }

    private AsyncNodeActionWithConfig runLeadAgentNode() {
        /**
         * 将 lead agent 封装成 graph 节点，便于外层继续统一处理状态和后处理。
         */
        return (state, config) -> {
            String agentInput = state.value(RuntimeStateKeys.AGENT_INPUT, String.class)
                    .orElseGet(() -> state.value(RuntimeStateKeys.USER_INPUT, ""));
            String parentThreadId = state.value(RuntimeStateKeys.THREAD_ID, String.class).orElse("runtime-lead");
            String parentRunId = state.value(RuntimeStateKeys.RUN_ID, String.class).orElse("run");
            try {
                var leadAgent = leadAgentFactory.create(LeadAgentDefinition.builder(chatModel)
                        .name("runtime-lead-agent")
                        .instruction("You are the Java DeerFlow backend lead agent.")
                        .tools(List.of(subTaskExecutor.taskTool(parentThreadId, parentRunId)))
                        .hooks(runtimeAgentEnhancementService.defaultHooks(chatModel))
                        .interceptors(runtimeAgentEnhancementService.defaultInterceptors())
                        .saver(runtimeCheckpointService.leadAgentSaver())
                        .build());
                RunnableConfig agentConfig = RunnableConfig.builder().threadId(parentThreadId).build();
                AssistantMessage assistantMessage = leadAgent.call(agentInput, agentConfig);

                Map<String, Object> leadThreadState = Optional.ofNullable(
                        leadAgent.getCompiledGraph().getState(agentConfig)
                ).map(snapshot -> snapshot.state().data()).orElse(Map.of());

                Map<String, Object> updates = new HashMap<>();
                updates.put(RuntimeStateKeys.ASSISTANT_OUTPUT, assistantMessage.getText());
                updates.put(RuntimeStateKeys.LEAD_THREAD_STATE, leadThreadState);
                updates.put(RuntimeStateKeys.TODOS, runtimeAgentEnhancementService.extractTodos(leadThreadState));
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
    private List<String> suggestionsFrom(PostRunGenerationResult postRunGenerationResult, OverAllState state) {
        if (postRunGenerationResult != null && !postRunGenerationResult.suggestions().isEmpty()) {
            return postRunGenerationResult.suggestions();
        }
        Object suggestions = state.value(RuntimeStateKeys.SUGGESTIONS).orElse(List.of());
        if (suggestions instanceof List<?> suggestionList) {
            return suggestionList.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private String titleFrom(PostRunGenerationResult postRunGenerationResult, OverAllState state, String fallbackMessage) {
        if (postRunGenerationResult != null && postRunGenerationResult.title() != null && !postRunGenerationResult.title().isBlank()) {
            return postRunGenerationResult.title();
        }
        return state.value(RuntimeStateKeys.TITLE, String.class)
                .orElseGet(() -> deriveTitle(fallbackMessage));
    }

    private String deriveTitle(String message) {
        String normalized = message.trim();
        return normalized.length() <= 48 ? normalized : normalized.substring(0, 48);
    }

    private String withClarification(String originalMessage, String clarificationComment) {
        return """
                %s

                Additional clarification from user:
                %s
                """.formatted(originalMessage, clarificationComment);
    }

    private List<TodoItem> todosFromState(OverAllState state) {
        if (state == null) {
            return List.of();
        }

        Object todos = state.value(RuntimeStateKeys.TODOS).orElse(null);
        if (todos instanceof List<?> todoList) {
            return todoList.stream()
                    .map(item -> objectMapper.convertValue(item, TodoItem.class))
                    .toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<ThreadMessage> messagesFromState(OverAllState state) {
        if (state == null) {
            return List.of();
        }
        Object leadThreadState = state.value(RuntimeStateKeys.LEAD_THREAD_STATE).orElse(Map.of());
        if (leadThreadState instanceof Map<?, ?> leadStateMap) {
            return runtimeAgentEnhancementService.extractMessages((Map<String, Object>) leadStateMap);
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
                snapshot.messages(),
                snapshot.todos(),
                snapshot.approval(),
                snapshot.suggestions(),
                snapshot.title()
        );
    }

    private void scheduleMemoryExtraction(ThreadContextMetadata threadContext,
                                          OverAllState state,
                                          ThreadStateSnapshot snapshot,
                                          String message) {
        String userId = normalizeOptionalUserId(threadContext == null ? null : threadContext.userId());
        if (userId == null) {
            return;
        }

        MemoryExtractionRequest request = new MemoryExtractionRequest(
                userId,
                snapshot.threadId(),
                snapshot.runId(),
                message,
                assistantOutputFrom(state),
                snapshot.title()
        );

        try {
            CompletableFuture<?> future = memoryExtractorJob.schedule(request);
            if (future != null) {
                future.whenComplete((ignored, exception) -> {
                    if (exception != null) {
                        logger.warn("Memory extraction completed exceptionally for thread {}", snapshot.threadId(), exception);
                    }
                });
            }
            threadEventService.emit(snapshot.threadId(), snapshot.runId(), RunEventType.MEMORY_SCHEDULED, Map.of("userId", userId));
        }
        catch (RuntimeException exception) {
            logger.warn("Failed to schedule memory extraction for thread {}", snapshot.threadId(), exception);
        }
    }

    private ThreadStateSnapshot withStatus(ThreadStateSnapshot snapshot,
                                           String runId,
                                           RunStatus targetStatus,
                                           ApprovalState approvalState) {
        return new ThreadStateSnapshot(
                snapshot.threadId(),
                runId,
                runStateMachine.transition(snapshot.runStatus(), targetStatus),
                snapshot.workspace(),
                currentUploads(snapshot.threadId()),
                currentArtifacts(snapshot.threadId()),
                snapshot.messages(),
                snapshot.todos(),
                approvalState,
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

    private List<SubTaskRecord> currentSubTasks(String threadId) {
        return subTaskExecutor.list(threadId);
    }

    private String assistantOutputFrom(OverAllState state) {
        return state.value(RuntimeStateKeys.ASSISTANT_OUTPUT, String.class).orElse("");
    }

    /**
     * 将 post-run 生成出的展示态同步回 graph checkpoint，
     * 让服务重建后可以仅依赖 checkpoint 恢复核心线程状态。
     */
    private void persistRuntimeCheckpointState(CompiledGraph runtimeGraph,
                                               RunnableConfig runtimeConfig,
                                               ThreadStateSnapshot snapshot) {
        try {
            runtimeGraph.updateState(runtimeConfig, runtimeProjectionState(snapshot));
        }
        catch (Exception exception) {
            logger.warn("Failed to update runtime checkpoint state for thread {}", snapshot.threadId(), exception);
        }
    }

    /**
     * 将线程展示态同步进 runtime checkpoint，避免额外维护独立的 thread-state 文件。
     */
    private void persistRuntimeThreadState(ThreadStateSnapshot snapshot) {
        CompiledGraph runtimeGraph = runtimeGraphFactory.create(noopLeadAgentNode(), runtimeCheckpointService.runtimeGraphSaver());
        RunnableConfig runtimeConfig = RunnableConfig.builder().threadId(snapshot.threadId()).build();
        try {
            if (runtimeGraph.stateOf(runtimeConfig).isPresent()) {
                runtimeGraph.updateState(runtimeConfig, runtimeProjectionState(snapshot));
                return;
            }
            runtimeCheckpointService.runtimeGraphSaver().put(
                    runtimeConfig,
                    Checkpoint.builder()
                            .id(UUID.randomUUID().toString())
                            .state(runtimeProjectionState(snapshot))
                            .nodeId(RuntimeGraphFactory.PREPARE_THREAD_NODE)
                            .nextNodeId(RuntimeGraphFactory.PREPARE_THREAD_NODE)
                            .build()
            );
        }
        catch (Exception exception) {
            throw new IllegalStateException("Failed to persist runtime checkpoint state for " + snapshot.threadId(), exception);
        }
    }

    /**
     * 从 runtime graph checkpoint 回填线程展示态。
     */
    private Optional<ThreadStateSnapshot> loadSnapshotFromCheckpoint(String threadId) {
        RunnableConfig runtimeConfig = RunnableConfig.builder().threadId(threadId).build();
        Optional<StateSnapshot> stateSnapshot = runtimeGraphFactory
                .create(noopLeadAgentNode(), runtimeCheckpointService.runtimeGraphSaver())
                .stateOf(runtimeConfig);
        if (stateSnapshot.isEmpty()) {
            return Optional.empty();
        }

        OverAllState state = stateSnapshot.get().state();
        ThreadWorkspace workspace = threadWorkspaceService.getOrCreateWorkspace(threadId);
        RunStatus recoveredStatus = state.value(RuntimeStateKeys.RUN_STATUS, RunStatus.class).orElse(RunStatus.IDLE);

        return Optional.of(new ThreadStateSnapshot(
                threadId,
                state.value(RuntimeStateKeys.RUN_ID, String.class).orElse(null),
                recoveredStatus,
                workspace.toState(),
                currentUploads(threadId),
                currentArtifacts(threadId),
                messagesFromState(state),
                todosFromState(state),
                approvalFromState(state),
                stringListValue(state.value(RuntimeStateKeys.SUGGESTIONS).orElse(List.of())),
                state.value(RuntimeStateKeys.TITLE, String.class).orElse(null)
        ));
    }

    /**
     * 兼容早期仅有审批 metadata 但尚未写入 checkpoint 的线程恢复。
     */
    private Optional<ThreadStateSnapshot> loadSnapshotFromPendingApproval(String threadId) {
        Optional<PendingApproval> pendingApproval = loadPendingApproval(threadId);
        if (pendingApproval.isEmpty() || !threadWorkspaceService.exists(threadId)) {
            return Optional.empty();
        }

        ThreadStateSnapshot baseSnapshot = loadSnapshotFromCheckpoint(threadId)
                .orElseGet(() -> idleSnapshot(threadWorkspaceService.getWorkspace(threadId)));

        ApprovalState approvalState = new ApprovalState(
                pendingApproval.get().approvalId(),
                pendingApproval.get().status(),
                pendingApproval.get().comment() == null ? pendingApproval.get().reason() : pendingApproval.get().comment()
        );

        RunStatus recoveredStatus = switch (pendingApproval.get().status()) {
            case WAITING, APPROVED -> RunStatus.WAITING_APPROVAL;
            case NEEDS_CLARIFICATION -> RunStatus.WAITING_CLARIFICATION;
            case REJECTED -> RunStatus.FAILED;
            default -> baseSnapshot.runStatus();
        };

        return Optional.of(new ThreadStateSnapshot(
                threadId,
                pendingApproval.get().runId(),
                recoveredStatus,
                baseSnapshot.workspace(),
                currentUploads(threadId),
                currentArtifacts(threadId),
                baseSnapshot.messages(),
                baseSnapshot.todos(),
                approvalState,
                baseSnapshot.suggestions(),
                baseSnapshot.title()
        ));
    }

    /**
     * 持久化待审批上下文，供审批提交和恢复执行使用。
     */
    private void persistPendingApproval(PendingApproval pendingApproval) {
        try {
            persistRuntimeAuxiliaryState(
                    pendingApproval.threadId(),
                    Map.of(RuntimeStateKeys.PENDING_APPROVAL, pendingApproval)
            );
            deleteLegacyPendingApprovalFile(pendingApproval.threadId());
        }
        catch (RuntimeException exception) {
            throw new IllegalStateException("Failed to persist pending approval for thread " + pendingApproval.threadId(), exception);
        }
    }

    /**
     * 读取线程当前待审批信息。
     */
    private Optional<PendingApproval> loadPendingApproval(String threadId) {
        Optional<PendingApproval> pendingApprovalFromCheckpoint = checkpointValue(
                threadId,
                RuntimeStateKeys.PENDING_APPROVAL,
                PendingApproval.class
        );
        if (pendingApprovalFromCheckpoint.isPresent()) {
            return pendingApprovalFromCheckpoint;
        }

        if (!threadWorkspaceService.exists(threadId)) {
            return Optional.empty();
        }

        Path approvalFile = pendingApprovalFile(threadId, false);
        if (!Files.isRegularFile(approvalFile)) {
            return Optional.empty();
        }
        try {
            PendingApproval pendingApproval = objectMapper.readValue(approvalFile.toFile(), PendingApproval.class);
            persistPendingApproval(pendingApproval);
            return Optional.of(pendingApproval);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to load pending approval for thread " + threadId, exception);
        }
    }

    /**
     * 删除待审批持久化文件。
     */
    private void deletePendingApproval(String threadId) {
        clearRuntimeAuxiliaryState(threadId, RuntimeStateKeys.PENDING_APPROVAL);
        deleteLegacyPendingApprovalFile(threadId);
    }

    private ThreadContextMetadata resolveThreadContext(String threadId, String requestedUserId) {
        String normalizedRequestedUserId = normalizeOptionalUserId(requestedUserId);
        Optional<ThreadContextMetadata> existingThreadContext = loadThreadContext(threadId);
        if (existingThreadContext.isPresent()) {
            String existingUserId = normalizeOptionalUserId(existingThreadContext.get().userId());
            if (existingUserId != null) {
                if (normalizedRequestedUserId != null && !existingUserId.equals(normalizedRequestedUserId)) {
                    throw new ThreadContextConflictException(threadId, existingUserId, normalizedRequestedUserId);
                }
                return new ThreadContextMetadata(existingUserId);
            }
        }

        if (normalizedRequestedUserId == null) {
            return existingThreadContext.orElse(new ThreadContextMetadata(null));
        }

        ThreadContextMetadata resolvedThreadContext = new ThreadContextMetadata(normalizedRequestedUserId);
        persistThreadContext(threadId, resolvedThreadContext);
        return resolvedThreadContext;
    }

    private void persistThreadContext(String threadId, ThreadContextMetadata threadContext) {
        if (threadContext == null || normalizeOptionalUserId(threadContext.userId()) == null) {
            return;
        }

        try {
            persistRuntimeAuxiliaryState(
                    threadId,
                    Map.of(RuntimeStateKeys.THREAD_CONTEXT, threadContext)
            );
            deleteLegacyThreadContextFile(threadId);
        }
        catch (RuntimeException exception) {
            throw new IllegalStateException("Failed to persist thread context for " + threadId, exception);
        }
    }

    private Optional<ThreadContextMetadata> loadThreadContext(String threadId) {
        Optional<ThreadContextMetadata> threadContextFromCheckpoint = checkpointValue(
                threadId,
                RuntimeStateKeys.THREAD_CONTEXT,
                ThreadContextMetadata.class
        );
        if (threadContextFromCheckpoint.isPresent()) {
            return threadContextFromCheckpoint;
        }

        if (!threadWorkspaceService.exists(threadId)) {
            return Optional.empty();
        }

        Path threadContextFile = threadContextFile(threadId, false);
        if (!Files.isRegularFile(threadContextFile)) {
            return Optional.empty();
        }

        try {
            ThreadContextMetadata threadContext = objectMapper.readValue(threadContextFile.toFile(), ThreadContextMetadata.class);
            persistThreadContext(threadId, threadContext);
            return Optional.of(threadContext);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to load thread context for " + threadId, exception);
        }
    }

    private String normalizeOptionalUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        return userId.trim();
    }

    private ThreadStateSnapshot currentSnapshot(String threadId, ThreadWorkspace workspace) {
        ThreadStateSnapshot snapshot = threadSnapshots.get(threadId);
        if (snapshot != null) {
            return snapshot;
        }
        return loadSnapshotFromCheckpoint(threadId)
                .or(() -> loadSnapshotFromPendingApproval(threadId))
                .orElseGet(() -> idleSnapshot(workspace));
    }

    private AsyncNodeActionWithConfig noopLeadAgentNode() {
        return (state, config) -> CompletableFuture.completedFuture(Map.of());
    }

    private ApprovalState approvalFromState(OverAllState state) {
        Object approval = state.value(RuntimeStateKeys.APPROVAL).orElse(NO_APPROVAL);
        if (approval instanceof ApprovalState approvalState) {
            return approvalState;
        }
        if (approval == null) {
            return NO_APPROVAL;
        }
        return objectMapper.convertValue(approval, ApprovalState.class);
    }

    private Map<String, Object> runtimeProjectionState(ThreadStateSnapshot snapshot) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(RuntimeStateKeys.THREAD_ID, snapshot.threadId());
        updates.put(RuntimeStateKeys.RUN_STATUS, snapshot.runStatus());
        updates.put(RuntimeStateKeys.APPROVAL, snapshot.approval());
        updates.put(RuntimeStateKeys.TODOS, snapshot.todos());
        updates.put(RuntimeStateKeys.SUGGESTIONS, snapshot.suggestions());
        if (snapshot.runId() != null && !snapshot.runId().isBlank()) {
            updates.put(RuntimeStateKeys.RUN_ID, snapshot.runId());
        }
        if (snapshot.title() != null && !snapshot.title().isBlank()) {
            updates.put(RuntimeStateKeys.TITLE, snapshot.title());
        }
        return updates;
    }

    private List<String> stringListValue(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        return items.stream().map(String::valueOf).toList();
    }

    private <T> Optional<T> checkpointValue(String threadId, String key, Class<T> valueType) {
        Optional<StateSnapshot> stateSnapshot = stateSnapshot(threadId);
        if (stateSnapshot.isEmpty()) {
            return Optional.empty();
        }

        Optional<T> value = stateSnapshot.get().state().value(key, valueType);
        if (value.isPresent()) {
            return value;
        }

        Object rawValue = stateSnapshot.get().state().value(key).orElse(null);
        if (rawValue == null) {
            return Optional.empty();
        }
        return Optional.of(objectMapper.convertValue(rawValue, valueType));
    }

    private Optional<StateSnapshot> stateSnapshot(String threadId) {
        RunnableConfig runtimeConfig = RunnableConfig.builder().threadId(threadId).build();
        return runtimeGraphFactory
                .create(noopLeadAgentNode(), runtimeCheckpointService.runtimeGraphSaver())
                .stateOf(runtimeConfig);
    }

    /**
     * 将非展示态辅助上下文写入 runtime checkpoint。
     */
    private void persistRuntimeAuxiliaryState(String threadId, Map<String, Object> updates) {
        CompiledGraph runtimeGraph = runtimeGraphFactory.create(noopLeadAgentNode(), runtimeCheckpointService.runtimeGraphSaver());
        RunnableConfig runtimeConfig = RunnableConfig.builder().threadId(threadId).build();
        try {
            if (runtimeGraph.stateOf(runtimeConfig).isPresent()) {
                runtimeGraph.updateState(runtimeConfig, updates);
                return;
            }

            ThreadWorkspace workspace = threadWorkspaceService.getOrCreateWorkspace(threadId);
            Map<String, Object> initialState = new HashMap<>(runtimeProjectionState(idleSnapshot(workspace)));
            initialState.putAll(updates);
            runtimeCheckpointService.runtimeGraphSaver().put(
                    runtimeConfig,
                    Checkpoint.builder()
                            .id(UUID.randomUUID().toString())
                            .state(initialState)
                            .nodeId(RuntimeGraphFactory.PREPARE_THREAD_NODE)
                            .nextNodeId(RuntimeGraphFactory.PREPARE_THREAD_NODE)
                            .build()
            );
        }
        catch (Exception exception) {
            throw new IllegalStateException("Failed to persist runtime auxiliary state for " + threadId, exception);
        }
    }

    private void clearRuntimeAuxiliaryState(String threadId, String key) {
        Optional<StateSnapshot> stateSnapshot = stateSnapshot(threadId);
        if (stateSnapshot.isEmpty()) {
            return;
        }

        CompiledGraph runtimeGraph = runtimeGraphFactory.create(noopLeadAgentNode(), runtimeCheckpointService.runtimeGraphSaver());
        RunnableConfig runtimeConfig = RunnableConfig.builder().threadId(threadId).build();
        try {
            runtimeGraph.updateState(runtimeConfig, Map.of(key, OverAllState.MARK_FOR_REMOVAL));
        }
        catch (Exception exception) {
            throw new IllegalStateException("Failed to clear runtime auxiliary state for " + threadId, exception);
        }
    }

    private void deleteLegacyPendingApprovalFile(String threadId) {
        if (!threadWorkspaceService.exists(threadId)) {
            return;
        }
        Path approvalFile = pendingApprovalFile(threadId, false);
        try {
            Files.deleteIfExists(approvalFile);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to delete legacy pending approval for thread " + threadId, exception);
        }
    }

    private void deleteLegacyThreadContextFile(String threadId) {
        if (!threadWorkspaceService.exists(threadId)) {
            return;
        }
        Path threadContextFile = threadContextFile(threadId, false);
        try {
            Files.deleteIfExists(threadContextFile);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to delete legacy thread context for " + threadId, exception);
        }
    }

    private Path pendingApprovalFile(String threadId, boolean createWorkspace) {
        return metadataDirectory(threadId, createWorkspace).resolve("pending-approval.json");
    }

    private Path threadContextFile(String threadId, boolean createWorkspace) {
        return metadataDirectory(threadId, createWorkspace).resolve("thread-context.json");
    }

    private Path metadataDirectory(String threadId, boolean createWorkspace) {
        ThreadWorkspace workspace = createWorkspace
                ? threadWorkspaceService.getOrCreateWorkspace(threadId)
                : threadWorkspaceService.getWorkspace(threadId);
        return workspace.threadRoot().resolve("metadata");
    }
}
