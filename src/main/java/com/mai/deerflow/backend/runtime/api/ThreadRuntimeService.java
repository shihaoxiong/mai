package com.mai.deerflow.backend.runtime.api;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mai.deerflow.backend.runtime.agent.LeadAgentDefinition;
import com.mai.deerflow.backend.runtime.agent.LeadAgentFactory;
import com.mai.deerflow.backend.runtime.agent.RuntimeAgentEnhancementService;
import com.mai.deerflow.backend.runtime.artifact.ArtifactService;
import com.mai.deerflow.backend.runtime.contract.ApprovalState;
import com.mai.deerflow.backend.runtime.contract.ApprovalStatus;
import com.mai.deerflow.backend.runtime.contract.ArtifactRef;
import com.mai.deerflow.backend.runtime.contract.RunEventType;
import com.mai.deerflow.backend.runtime.contract.RunStatus;
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
 * 负责把线程工作区、runtime graph、lead agent、审批恢复、事件流和快照持久化串成一条完整主链路。
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
    private final ConcurrentMap<String, ThreadStateSnapshot> threadSnapshots = new ConcurrentHashMap<>();

    public ThreadRuntimeService(ThreadWorkspaceService threadWorkspaceService,
                                RuntimeGraphFactory runtimeGraphFactory,
                                LeadAgentFactory leadAgentFactory,
                                RuntimeAgentEnhancementService runtimeAgentEnhancementService,
                                ChatModel chatModel,
                                RunStateMachine runStateMachine,
                                ObjectMapper objectMapper,
                                UploadService uploadService,
                                ArtifactService artifactService,
                                ThreadEventService threadEventService,
                                MemoryExtractorJob memoryExtractorJob,
                                SubTaskExecutor subTaskExecutor,
                                PostRunGenerationService postRunGenerationService) {
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
    }

    /**
     * 创建线程并写入初始快照。
     */
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

    /**
     * 获取线程当前状态；若内存中不存在，则尝试从磁盘快照恢复。
     */
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
        ThreadContextMetadata threadContext = resolveThreadContext(threadId, userId);
        ThreadStateSnapshot currentSnapshot = threadSnapshots.getOrDefault(threadId, idleSnapshot(workspace));
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
                    currentSnapshot.todos(),
                    new ApprovalState(approvalId, ApprovalStatus.REJECTED, request.comment()),
                    currentSnapshot.suggestions(),
                    currentSnapshot.title()
            );
            threadSnapshots.put(threadId, rejectedSnapshot);
            persistSnapshot(rejectedSnapshot);
            threadEventService.emit(threadId, pendingApproval.runId(), RunEventType.RUN_FAILED, rejectedSnapshot.approval());
            return rejectedSnapshot;
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
                currentSnapshot.todos(),
                new ApprovalState(approvalId, ApprovalStatus.APPROVED, request.comment()),
                currentSnapshot.suggestions(),
                currentSnapshot.title()
        );
        threadSnapshots.put(threadId, approvedSnapshot);
        persistSnapshot(approvedSnapshot);
        return approvedSnapshot;
    }

    /**
     * 在审批已通过的前提下恢复线程执行。
     */
    public ThreadStateSnapshot resumeThread(String threadId, ResumeThreadRequest request) {
        PendingApproval pendingApproval = loadPendingApproval(threadId)
                .orElseThrow(() -> new ApprovalOperationException("No pending approval exists for thread " + threadId));

        if (pendingApproval.status() != ApprovalStatus.APPROVED) {
            throw new ApprovalOperationException("Pending approval must be approved before resume");
        }

        ThreadStateSnapshot currentSnapshot = getThread(threadId);
        ThreadStateSnapshot resumedSnapshot = executeRun(
                threadId,
                pendingApproval.message(),
                pendingApproval.runId(),
                currentSnapshot,
                new ApprovalState(pendingApproval.approvalId(), ApprovalStatus.APPROVED, pendingApproval.comment()),
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
                currentSnapshot.todos(),
                new ApprovalState(approvalId, ApprovalStatus.WAITING, reason),
                currentSnapshot.suggestions(),
                currentSnapshot.title()
        );

        threadSnapshots.put(threadId, waitingSnapshot);
        persistSnapshot(waitingSnapshot);
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

        ThreadStateSnapshot runningSnapshot = withStatus(currentSnapshot, runId, RunStatus.RUNNING, approvalState);
        threadSnapshots.put(threadId, runningSnapshot);
        persistSnapshot(runningSnapshot);
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

            Optional<OverAllState> result = runtimeGraphFactory.create(runLeadAgentNode).invoke(
                    initialState,
                    RunnableConfig.builder().threadId(threadId).build()
            );

            OverAllState state = result.orElseThrow(() -> new IllegalStateException("Runtime graph returned no state"));
            List<UploadRef> uploads = currentUploads(threadId);
            List<ArtifactRef> artifacts = currentArtifacts(threadId);
            List<TodoItem> todos = extractTodosFromLeadState(state);
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
                    todos,
                    approvalState,
                    suggestionsFrom(postRunGenerationResult, state),
                    titleFrom(postRunGenerationResult, state, message)
            );

            threadSnapshots.put(threadId, snapshot);
            persistSnapshot(snapshot);
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
                    List.of(),
                    approvalState,
                    List.of(),
                    deriveTitle(message)
            );
            threadSnapshots.put(threadId, failedSnapshot);
            persistSnapshot(failedSnapshot);
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
            String rawUserInput = state.value(RuntimeStateKeys.USER_INPUT, "");
            String agentInput = state.value(RuntimeStateKeys.AGENT_INPUT, String.class)
                    .orElseGet(() -> state.value(RuntimeStateKeys.USER_INPUT, ""));
            String parentThreadId = state.value(RuntimeStateKeys.THREAD_ID, String.class).orElse("runtime-lead");
            String parentRunId = state.value(RuntimeStateKeys.RUN_ID, String.class).orElse("run");
            String agentThreadId = "%s:%s".formatted(
                    parentThreadId,
                    parentRunId
            );
            try {
                var leadAgent = leadAgentFactory.create(LeadAgentDefinition.builder(chatModel)
                        .name("runtime-lead-agent")
                        .instruction("You are the Java DeerFlow backend lead agent.")
                        .tools(List.of(subTaskExecutor.taskTool(parentThreadId, parentRunId)))
                        .hooks(runtimeAgentEnhancementService.defaultHooks(chatModel))
                        .interceptors(runtimeAgentEnhancementService.defaultInterceptors())
                        .saver(new MemorySaver())
                        .build());
                RunnableConfig agentConfig = RunnableConfig.builder().threadId(agentThreadId).build();
                AssistantMessage assistantMessage = leadAgent.call(agentInput, agentConfig);

                Map<String, Object> leadThreadState = Optional.ofNullable(
                        leadAgent.getCompiledGraph().getState(agentConfig)
                ).map(snapshot -> snapshot.state().data()).orElse(Map.of());

                Map<String, Object> updates = new HashMap<>();
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
        return state.value("assistantOutput", String.class).orElse("");
    }

    /**
     * 将线程快照写入 metadata 目录，供恢复链路读取。
     */
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

    /**
     * 从 metadata 中恢复线程快照。
     */
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

    /**
     * 持久化待审批上下文，供审批提交和恢复执行使用。
     */
    private void persistPendingApproval(PendingApproval pendingApproval) {
        Path approvalFile = pendingApprovalFile(pendingApproval.threadId());
        try {
            Files.createDirectories(approvalFile.getParent());
            objectMapper.writeValue(approvalFile.toFile(), pendingApproval);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to persist pending approval for thread " + pendingApproval.threadId(), exception);
        }
    }

    /**
     * 读取线程当前待审批信息。
     */
    private Optional<PendingApproval> loadPendingApproval(String threadId) {
        Path approvalFile = pendingApprovalFile(threadId);
        if (!Files.isRegularFile(approvalFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(approvalFile.toFile(), PendingApproval.class));
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to load pending approval for thread " + threadId, exception);
        }
    }

    /**
     * 删除待审批持久化文件。
     */
    private void deletePendingApproval(String threadId) {
        Path approvalFile = pendingApprovalFile(threadId);
        try {
            Files.deleteIfExists(approvalFile);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to delete pending approval for thread " + threadId, exception);
        }
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

        Path threadContextFile = threadContextFile(threadId);
        try {
            Files.createDirectories(threadContextFile.getParent());
            objectMapper.writeValue(threadContextFile.toFile(), threadContext);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to persist thread context for " + threadId, exception);
        }
    }

    private Optional<ThreadContextMetadata> loadThreadContext(String threadId) {
        if (!threadWorkspaceService.exists(threadId)) {
            return Optional.empty();
        }

        Path threadContextFile = threadContextFile(threadId);
        if (!Files.isRegularFile(threadContextFile)) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.readValue(threadContextFile.toFile(), ThreadContextMetadata.class));
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

    private Path snapshotFile(ThreadWorkspace workspace) {
        return workspace.threadRoot().resolve("metadata").resolve("thread-state.json");
    }

    private Path pendingApprovalFile(String threadId) {
        return threadWorkspaceService.getOrCreateWorkspace(threadId)
                .threadRoot()
                .resolve("metadata")
                .resolve("pending-approval.json");
    }

    private Path threadContextFile(String threadId) {
        return threadWorkspaceService.getOrCreateWorkspace(threadId)
                .threadRoot()
                .resolve("metadata")
                .resolve("thread-context.json");
    }
}
