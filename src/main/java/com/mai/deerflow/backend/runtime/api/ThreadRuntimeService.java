package com.mai.deerflow.backend.runtime.api;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.mai.deerflow.backend.runtime.contract.RunEventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mai.deerflow.backend.runtime.agent.AgentClarificationRequestedException;
import com.mai.deerflow.backend.runtime.agent.AskClarificationRequest;
import com.mai.deerflow.backend.runtime.agent.LeadAgentDefinition;
import com.mai.deerflow.backend.runtime.agent.LeadAgentFactory;
import com.mai.deerflow.backend.runtime.agent.RuntimeAgentEnhancementService;
import com.mai.deerflow.backend.runtime.agent.RuntimeDeferredToolFilterInterceptor;
import com.mai.deerflow.backend.runtime.agent.RuntimeDeferredToolService;
import com.mai.deerflow.backend.runtime.agent.RuntimeLeadAgentPromptService;
import com.mai.deerflow.backend.runtime.agent.RuntimeTodoReminderInterceptor;
import com.mai.deerflow.backend.runtime.agent.RuntimeThreadContextInterceptor;
import com.mai.deerflow.backend.runtime.agent.RuntimeToolExecutionExceptionProcessor;
import com.mai.deerflow.backend.runtime.agent.ViewImageRequest;
import com.mai.deerflow.backend.runtime.agent.ViewedImageData;
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
import com.mai.deerflow.backend.runtime.graph.RuntimeStateKeys;
import com.mai.deerflow.backend.runtime.memory.*;
import com.mai.deerflow.backend.runtime.model.ModelDescriptor;
import com.mai.deerflow.backend.runtime.model.ModelRegistryService;
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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
/**
 * 线程运行时的核心编排服务。
 *
 * 负责把线程工作区、lead agent、审批恢复和事件流串成一条完整主链路。
 */
public class ThreadRuntimeService {

    private static final ApprovalState NO_APPROVAL = new ApprovalState(null, ApprovalStatus.NONE, null);
    private static final Logger logger = LoggerFactory.getLogger(ThreadRuntimeService.class);
    private static final String LEAD_AGENT_START_NODE = "__START__";
    private static final String LEAD_AGENT_MODEL_NODE = "_AGENT_MODEL_";

    private final ThreadWorkspaceService threadWorkspaceService;
    private final LeadAgentFactory leadAgentFactory;
    private final RuntimeAgentEnhancementService runtimeAgentEnhancementService;
    private final RuntimeLeadAgentPromptService runtimeLeadAgentPromptService;
    private final MemoryInjectionService memoryInjectionService;
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
    private RuntimeDeferredToolService runtimeDeferredToolService;
    private ModelRegistryService modelRegistryService;
    private final ConcurrentMap<String, ThreadStateSnapshot> threadSnapshots = new ConcurrentHashMap<>();

    public ThreadRuntimeService(ThreadWorkspaceService threadWorkspaceService,
                                LeadAgentFactory leadAgentFactory,
                                RuntimeAgentEnhancementService runtimeAgentEnhancementService,
                                RuntimeLeadAgentPromptService runtimeLeadAgentPromptService,
                                MemoryInjectionService memoryInjectionService,
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
        this.leadAgentFactory = leadAgentFactory;
        this.runtimeAgentEnhancementService = runtimeAgentEnhancementService;
        this.runtimeLeadAgentPromptService = runtimeLeadAgentPromptService;
        this.memoryInjectionService = memoryInjectionService;
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

    @Autowired(required = false)
    public void setRuntimeDeferredToolService(RuntimeDeferredToolService runtimeDeferredToolService) {
        this.runtimeDeferredToolService = runtimeDeferredToolService;
    }

    @Autowired(required = false)
    public void setModelRegistryService(ModelRegistryService modelRegistryService) {
        this.modelRegistryService = modelRegistryService;
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
        persistLeadAgentThreadState(snapshot);
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
        return runThread(threadId, message, approvalRequired, approvalReason, userId, RequestedRuntimeRunOptions.empty());
    }

    /**
     * 执行一次线程运行，并允许调用方预先指定 runId，便于 run 级 SSE 先建订阅再启动执行。
     */
    public ThreadStateSnapshot runThread(String threadId,
                                         String message,
                                         boolean approvalRequired,
                                         String approvalReason,
                                         String userId,
                                         RequestedRuntimeRunOptions requestedRunOptions) {
        return runThread(threadId, message, approvalRequired, approvalReason, userId, requestedRunOptions, null);
    }

    /**
     * 执行一次线程运行，并允许调用方预先指定 runId，便于 run 级 SSE 先建订阅再启动执行。
     */
    public ThreadStateSnapshot runThread(String threadId,
                                         String message,
                                         boolean approvalRequired,
                                         String approvalReason,
                                         String userId,
                                         RequestedRuntimeRunOptions requestedRunOptions,
                                         String requestedRunId) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }

        ThreadWorkspace workspace = threadWorkspaceService.getOrCreateWorkspace(threadId);
        ThreadStateSnapshot currentSnapshot = currentSnapshot(threadId, workspace);
        ThreadContextMetadata threadContext = resolveThreadContext(threadId, userId);
        RuntimeRunOptions runOptions = resolveRunOptions(requestedRunOptions);
        String runId = requestedRunId == null || requestedRunId.isBlank()
                ? UUID.randomUUID().toString()
                : requestedRunId.trim();

        if (approvalRequired) {
            return createPendingApproval(threadId, runId, message, approvalReason, currentSnapshot, workspace, runOptions);
        }

        return executeRun(threadId, message, runId, currentSnapshot, NO_APPROVAL, threadContext, runOptions);
    }

    /**
     * 以真正的模型流式输出执行一次线程运行，并返回 run 级事件流。
     */
    public Flux<RunEventEnvelope<Object>> runThreadStream(String threadId,
                                                          String message,
                                                          boolean approvalRequired,
                                                          String approvalReason,
                                                          String userId,
                                                          RequestedRuntimeRunOptions requestedRunOptions,
                                                          String requestedRunId) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }

        ThreadWorkspace workspace = threadWorkspaceService.getOrCreateWorkspace(threadId);
        ThreadStateSnapshot currentSnapshot = currentSnapshot(threadId, workspace);
        ThreadContextMetadata threadContext = resolveThreadContext(threadId, userId);
        RuntimeRunOptions runOptions = resolveRunOptions(requestedRunOptions);
        String runId = requestedRunId == null || requestedRunId.isBlank()
                ? UUID.randomUUID().toString()
                : requestedRunId.trim();

        if (approvalRequired) {
            ThreadStateSnapshot waitingSnapshot = createPendingApproval(
                    threadId,
                    runId,
                    message,
                    approvalReason,
                    currentSnapshot,
                    workspace,
                    runOptions
            );
            return Flux.just(new RunEventEnvelope<>(threadId, runId, RunEventType.APPROVAL_REQUIRED, waitingSnapshot.approval()));
        }

        return executeRunStream(threadId, message, runId, currentSnapshot, NO_APPROVAL, threadContext, runOptions);
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
            persistLeadAgentThreadState(rejectedSnapshot);
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
                    clarificationRequest,
                    pendingApproval.runOptions()
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
            persistLeadAgentThreadState(clarificationSnapshot);
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
                request.comment(),
                pendingApproval.runOptions()
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
        persistLeadAgentThreadState(approvedSnapshot);
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
                resolveThreadContext(threadId, null),
                effectiveRunOptions(pendingApproval.runOptions())
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
                                                      ThreadWorkspace workspace,
                                                      RuntimeRunOptions runOptions) {
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
                null,
                runOptions
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
        persistLeadAgentThreadState(waitingSnapshot);
        persistPendingApproval(pendingApproval);
        threadEventService.emit(threadId, runId, RunEventType.APPROVAL_REQUIRED, waitingSnapshot.approval());
        return waitingSnapshot;
    }

    /**
     * 直接调用 lead agent 执行主链路。
     */
    private ThreadStateSnapshot executeRun(String threadId,
                                           String message,
                                           String runId,
                                           ThreadStateSnapshot currentSnapshot,
                                           ApprovalState approvalState,
                                           ThreadContextMetadata threadContext,
                                           RuntimeRunOptions runOptions) {
        ThreadWorkspace workspace = threadWorkspaceService.getOrCreateWorkspace(threadId);
        RunnableConfig runtimeConfig = RunnableConfig.builder().threadId(threadId).build();
        var leadAgent = runtimeLeadAgent(threadId, runId, runOptions, threadContext == null ? null : threadContext.userId());

        ThreadStateSnapshot runningSnapshot = withStatus(currentSnapshot, runId, RunStatus.RUNNING, approvalState);
        threadSnapshots.put(threadId, runningSnapshot);
        persistLeadAgentThreadState(runningSnapshot);
        threadEventService.emit(threadId, runId, RunEventType.RUN_STARTED, Map.of("status", RunStatus.RUNNING.name()));

        try {
            AssistantMessage assistantMessage = leadAgent.call(message, runtimeConfig);
            OverAllState state = Optional.ofNullable(leadAgent.getCompiledGraph().getState(runtimeConfig))
                    .map(StateSnapshot::state)
                    .orElseThrow(() -> new IllegalStateException("Lead agent returned no state"));
            List<UploadRef> uploads = currentUploads(threadId);
            List<ArtifactRef> artifacts = currentArtifacts(threadId);
            List<ThreadMessage> messages = messagesFromState(state);
            List<TodoItem> todos = todosFromState(state);
            List<SubTaskRecord> subTasks = currentSubTasks(threadId);
            PostRunGenerationResult postRunGenerationResult = postRunGenerationService.generate(
                    message,
                    assistantMessage.getText(),
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

            persistLeadAgentThreadState(snapshot);
            threadSnapshots.put(threadId, snapshot);
            scheduleMemoryExtraction(threadContext, state, snapshot, message);
            threadEventService.emit(threadId, runId, RunEventType.RUN_COMPLETED, snapshot);
            return snapshot;
        }
        catch (Exception exception) {
            AgentClarificationRequestedException clarificationException = clarificationException(exception);
            if (clarificationException != null) {
                return pauseForClarification(
                        threadId,
                        runId,
                        message,
                        runningSnapshot,
                        workspace,
                        runtimeConfig,
                        clarificationException,
                        runOptions
                );
            }

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
            persistLeadAgentThreadState(failedSnapshot);
            threadEventService.emit(threadId, runId, RunEventType.RUN_FAILED, Map.of("message", String.valueOf(exception.getMessage())));
            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Lead agent execution failed for thread " + threadId, exception);
        }
    }

    private Flux<RunEventEnvelope<Object>> executeRunStream(String threadId,
                                                            String message,
                                                            String runId,
                                                            ThreadStateSnapshot currentSnapshot,
                                                            ApprovalState approvalState,
                                                            ThreadContextMetadata threadContext,
                                                            RuntimeRunOptions runOptions) {
        ThreadWorkspace workspace = threadWorkspaceService.getOrCreateWorkspace(threadId);
        RunnableConfig runtimeConfig = RunnableConfig.builder().threadId(threadId).build();
        var leadAgent = runtimeLeadAgent(threadId, runId, runOptions, threadContext == null ? null : threadContext.userId());

        ThreadStateSnapshot runningSnapshot = withStatus(currentSnapshot, runId, RunStatus.RUNNING, approvalState);
        threadSnapshots.put(threadId, runningSnapshot);
        persistLeadAgentThreadState(runningSnapshot);

        RunEventEnvelope<Object> started = new RunEventEnvelope<>(
                threadId,
                runId,
                RunEventType.RUN_STARTED,
                Map.of("status", RunStatus.RUNNING.name())
        );

        Flux<RunEventEnvelope<Object>> streamedEvents;
        try {
            streamedEvents = leadAgent.streamMessages(message, runtimeConfig)
                    .flatMap(streamMessage -> streamEventsFromMessage(threadId, runId, streamMessage));
        }
        catch (Exception exception) {
            return handleStreamFailure(
                    threadId,
                    message,
                    runId,
                    runningSnapshot,
                    approvalState,
                    threadContext,
                    workspace,
                    runtimeConfig,
                    runOptions,
                    exception
            );
        }

        Mono<RunEventEnvelope<Object>> completed = Mono.fromCallable(() -> completeStreamRun(
                threadId,
                message,
                runId,
                runningSnapshot,
                approvalState,
                threadContext,
                workspace,
                runtimeConfig,
                leadAgent
        ));

        return Flux.concat(Flux.just(started), streamedEvents, completed)
                .doOnNext(this::emitEvent)
                .onErrorResume(exception -> handleStreamFailure(
                        threadId,
                        message,
                        runId,
                        runningSnapshot,
                        approvalState,
                        threadContext,
                        workspace,
                        runtimeConfig,
                        runOptions,
                        exception
                ));
    }

    private Flux<RunEventEnvelope<Object>> streamEventsFromMessage(String threadId,
                                                                   String runId,
                                                                   Message streamMessage) {
        if (streamMessage instanceof AssistantMessage assistantMessage) {
            List<RunEventEnvelope<Object>> events = new ArrayList<>();
            if (assistantMessage.getText() != null && !assistantMessage.getText().isBlank()) {
                events.add(new RunEventEnvelope<>(threadId, runId, RunEventType.TOKEN_DELTA, assistantMessage.getText()));
            }
            if (assistantMessage.hasToolCalls()) {
                events.add(new RunEventEnvelope<>(threadId, runId, RunEventType.TOOL_CALL_STARTED, assistantMessage.getToolCalls()));
            }
            return Flux.fromIterable(events);
        }

        if (streamMessage instanceof ToolResponseMessage toolResponseMessage) {
            return Flux.just(new RunEventEnvelope<>(
                    threadId,
                    runId,
                    RunEventType.TOOL_CALL_COMPLETED,
                    toolResponseMessage.getResponses()
            ));
        }

        return Flux.empty();
    }

    private RunEventEnvelope<Object> completeStreamRun(String threadId,
                                                       String message,
                                                       String runId,
                                                       ThreadStateSnapshot runningSnapshot,
                                                       ApprovalState approvalState,
                                                       ThreadContextMetadata threadContext,
                                                       ThreadWorkspace workspace,
                                                       RunnableConfig runtimeConfig,
                                                       com.alibaba.cloud.ai.graph.agent.ReactAgent leadAgent) {
        OverAllState state = Optional.ofNullable(leadAgent.getCompiledGraph().getState(runtimeConfig))
                .map(StateSnapshot::state)
                .orElseThrow(() -> new IllegalStateException("Lead agent returned no state"));
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

        persistLeadAgentThreadState(snapshot);
        threadSnapshots.put(threadId, snapshot);
        scheduleMemoryExtraction(threadContext, state, snapshot, message);
        return new RunEventEnvelope<>(threadId, runId, RunEventType.RUN_COMPLETED, snapshot);
    }

    private Flux<RunEventEnvelope<Object>> handleStreamFailure(String threadId,
                                                               String message,
                                                               String runId,
                                                               ThreadStateSnapshot currentSnapshot,
                                                               ApprovalState approvalState,
                                                               ThreadContextMetadata threadContext,
                                                               ThreadWorkspace workspace,
                                                               RunnableConfig runtimeConfig,
                                                               RuntimeRunOptions runOptions,
                                                               Throwable exception) {
        AgentClarificationRequestedException clarificationException = clarificationException(exception);
        if (clarificationException != null) {
            ThreadStateSnapshot clarificationSnapshot = pauseForClarification(
                    threadId,
                    runId,
                    message,
                    currentSnapshot,
                    workspace,
                    runtimeConfig,
                    clarificationException,
                    runOptions
            );
            RunEventEnvelope<Object> event = new RunEventEnvelope<>(
                    threadId,
                    runId,
                    RunEventType.APPROVAL_REQUIRED,
                    clarificationSnapshot.approval()
            );
            emitEvent(event);
            return Flux.just(event);
        }

        Optional<OverAllState> state = safeState(runtimeConfig);
        List<ThreadMessage> messages = state.map(this::messagesFromState)
                .filter(values -> !values.isEmpty())
                .orElse(currentSnapshot.messages());
        List<TodoItem> todos = state.map(this::todosFromState).orElse(currentSnapshot.todos());

        ThreadStateSnapshot failedSnapshot = new ThreadStateSnapshot(
                threadId,
                runId,
                runStateMachine.transition(currentSnapshot.runStatus(), RunStatus.FAILED),
                workspace.toState(),
                currentUploads(threadId),
                currentArtifacts(threadId),
                messages,
                todos,
                approvalState,
                currentSnapshot.suggestions(),
                currentSnapshot.title() == null ? deriveTitle(message) : currentSnapshot.title()
        );
        threadSnapshots.put(threadId, failedSnapshot);
        persistLeadAgentThreadState(failedSnapshot);

        RunEventEnvelope<Object> event = new RunEventEnvelope<>(
                threadId,
                runId,
                RunEventType.RUN_FAILED,
                Map.of("message", String.valueOf(exception.getMessage()))
        );
        emitEvent(event);
        return Flux.just(event);
    }

    private void emitEvent(RunEventEnvelope<Object> event) {
        if (event != null) {
            threadEventService.emit(event.threadId(), event.runId(), event.eventType(), event.payload());
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

    private com.alibaba.cloud.ai.graph.agent.ReactAgent runtimeLeadAgent(String threadId, String runId) {
        return runtimeLeadAgent(threadId, runId, RuntimeRunOptions.defaults(), null);
    }

    private com.alibaba.cloud.ai.graph.agent.ReactAgent runtimeLeadAgent(String threadId,
                                                                         String runId,
                                                                         RuntimeRunOptions runOptions,
                                                                         String userId) {
        RuntimeRunOptions effectiveRunOptions = effectiveRunOptions(runOptions);
        List<ToolCallback> tools = new ArrayList<>();
        if (effectiveRunOptions.subagentEnabled()) {
            tools.add(subTaskExecutor.taskTool(threadId, runId));
        }
        tools.add(askClarificationTool());
        tools.add(viewImageTool(threadId));
        List<String> deferredToolNames = List.of();
        if (runtimeDeferredToolService != null) {
            List<ToolCallback> deferredTools = runtimeDeferredToolService.deferredTools(threadId);
            if (!deferredTools.isEmpty()) {
                tools.add(runtimeDeferredToolService.toolSearchTool(threadId, deferredTools));
                tools.addAll(deferredTools);
                deferredToolNames = deferredTools.stream()
                        .map(tool -> tool.getToolDefinition().name())
                        .toList();
            }
        }

        com.alibaba.cloud.ai.graph.agent.ReactAgent agent = leadAgentFactory.create(LeadAgentDefinition.builder(chatModel)
                .name("runtime-lead-agent")
                .instruction(runtimeLeadAgentPromptService.instruction())
                .systemPrompt(runtimeLeadAgentPromptService.systemPrompt(threadId, effectiveRunOptions, userId))
                .chatOptions(chatOptionsFor(effectiveRunOptions))
                .tools(tools)
                .hooks(runtimeAgentEnhancementService.defaultHooks(chatModel))
                .interceptors(runtimeInterceptors(userId, threadId, deferredToolNames, effectiveRunOptions))
                .toolExecutionExceptionProcessor(new RuntimeToolExecutionExceptionProcessor())
                .saver(runtimeCheckpointService.leadAgentSaver())
                .build());
        agent.asNode(false, false);
        return agent;
    }

    private List<com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor> runtimeInterceptors(String userId,String threadId,
                                                                                               List<String> deferredToolNames,
                                                                                               RuntimeRunOptions runOptions) {
        RuntimeRunOptions effectiveRunOptions = effectiveRunOptions(runOptions);
        List<com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor> interceptors =
                new ArrayList<>(runtimeAgentEnhancementService.defaultInterceptors(effectiveRunOptions));
        interceptors.add(0, new RuntimeThreadContextInterceptor(threadId, runtimeLeadAgentPromptService));
        if (effectiveRunOptions.planModeEnabled()) {
            interceptors.add(1, new RuntimeTodoReminderInterceptor(threadId, runtimeCheckpointService, runtimeAgentEnhancementService));
        }
        interceptors.add(new MemoryInjectionInterceptor(userId, memoryInjectionService));
        if (!deferredToolNames.isEmpty()) {
            interceptors.add(effectiveRunOptions.planModeEnabled() ? 2 : 1,
                    new RuntimeDeferredToolFilterInterceptor(deferredToolNames, objectMapper));
        }
        interceptors.addAll(runtimeAgentEnhancementService.supplementalInterceptors());
        return List.copyOf(interceptors);
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
        return runtimeAgentEnhancementService.extractTodos(state.data());
    }

    @SuppressWarnings("unchecked")
    private List<ThreadMessage> messagesFromState(OverAllState state) {
        if (state == null) {
            return List.of();
        }
        return runtimeAgentEnhancementService.extractMessages(state.data());
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
        List<ThreadMessage> messages = messagesFromState(state);
        for (int index = messages.size() - 1; index >= 0; index--) {
            ThreadMessage message = messages.get(index);
            if ("assistant".equals(message.role())) {
                return message.content();
            }
        }
        return "";
    }

    private ToolCallback askClarificationTool() {
        return FunctionToolCallback
                .builder("ask_clarification", (AskClarificationRequest request) -> "clarification requested")
                .description("""
                        Request additional user clarification and pause the current run.
                        Use this tool when requirements are missing, ambiguous, risky, or when you need the user to choose an approach before continuing.
                        """)
                .inputType(AskClarificationRequest.class)
                .build();
    }

    private ToolCallback viewImageTool(String threadId) {
        return FunctionToolCallback
                .builder("view_image", (ViewImageRequest request) -> viewImagePayloadJson(threadId, request))
                .description("""
                        Load an image from the current thread workspace and make it available for the next model turn.
                        The path must be a thread-scoped virtual path such as /uploads/example.png or /outputs/chart.jpg.
                        """)
                .inputType(ViewImageRequest.class)
                .build();
    }

    private String viewImagePayloadJson(String threadId, ViewImageRequest request) {
        try {
            return objectMapper.writeValueAsString(loadViewedImage(threadId, request));
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize viewed image payload", exception);
        }
    }

    private ViewedImageData loadViewedImage(String threadId, ViewImageRequest request) {
        String virtualPath = request == null ? null : request.path();
        if (virtualPath == null || virtualPath.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }

        Path realPath = threadWorkspaceService.resolveVirtualPath(threadId, virtualPath);
        if (!Files.isRegularFile(realPath)) {
            throw new IllegalArgumentException("Image file not found: " + virtualPath);
        }

        String mimeType = imageMimeType(realPath);
        if (mimeType == null) {
            throw new IllegalArgumentException("Unsupported image file: " + virtualPath);
        }

        try {
            byte[] bytes = Files.readAllBytes(realPath);
            return new ViewedImageData(
                    virtualPath,
                    mimeType,
                    Base64.getEncoder().encodeToString(bytes)
            );
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to read image file " + virtualPath, exception);
        }
    }

    private String imageMimeType(Path path) {
        try {
            String probed = Files.probeContentType(path);
            if (probed != null && probed.startsWith("image/")) {
                return probed;
            }
        }
        catch (IOException ignored) {
        }

        String filename = path.getFileName().toString().toLowerCase();
        if (filename.endsWith(".png")) {
            return "image/png";
        }
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (filename.endsWith(".gif")) {
            return "image/gif";
        }
        if (filename.endsWith(".webp")) {
            return "image/webp";
        }
        return null;
    }

    private ThreadStateSnapshot pauseForClarification(String threadId,
                                                      String runId,
                                                      String message,
                                                      ThreadStateSnapshot currentSnapshot,
                                                      ThreadWorkspace workspace,
                                                      RunnableConfig runtimeConfig,
                                                      AgentClarificationRequestedException clarificationException,
                                                      RuntimeRunOptions runOptions) {
        String approvalId = UUID.randomUUID().toString();
        String clarificationPrompt = clarificationException.displayMessage();
        Optional<OverAllState> state = safeState(runtimeConfig);
        if (state.isEmpty()) {
            persistLeadAgentAuxiliaryState(
                    threadId,
                    Map.of("messages", List.of(
                            new UserMessage(message),
                            new AssistantMessage(clarificationPrompt)
                    ))
            );
            state = safeState(runtimeConfig);
        }

        PendingApproval pendingApproval = new PendingApproval(
                threadId,
                runId,
                approvalId,
                message,
                clarificationPrompt,
                ApprovalStatus.NEEDS_CLARIFICATION,
                null,
                runOptions
        );

        ThreadStateSnapshot clarificationSnapshot = new ThreadStateSnapshot(
                threadId,
                runId,
                runStateMachine.transition(currentSnapshot.runStatus(), RunStatus.WAITING_CLARIFICATION),
                workspace.toState(),
                currentUploads(threadId),
                currentArtifacts(threadId),
                clarificationMessages(state.orElse(null), currentSnapshot, message, clarificationPrompt),
                clarificationTodos(state.orElse(null), currentSnapshot),
                new ApprovalState(approvalId, ApprovalStatus.NEEDS_CLARIFICATION, clarificationPrompt),
                currentSnapshot.suggestions(),
                currentSnapshot.title() == null ? deriveTitle(message) : currentSnapshot.title()
        );
        threadSnapshots.put(threadId, clarificationSnapshot);
        persistLeadAgentThreadState(clarificationSnapshot);
        persistPendingApproval(pendingApproval);
        threadEventService.emit(threadId, runId, RunEventType.APPROVAL_REQUIRED, clarificationSnapshot.approval());
        return clarificationSnapshot;
    }

    /**
     * 将线程展示态同步回 lead agent checkpoint。
     */
    private void persistLeadAgentThreadState(ThreadStateSnapshot snapshot) {
        var leadAgent = runtimeLeadAgent(snapshot.threadId(), snapshot.runId() == null ? "state" : snapshot.runId());
        RunnableConfig runtimeConfig = RunnableConfig.builder().threadId(snapshot.threadId()).build();
        try {
            if (leadAgent.getCompiledGraph().stateOf(runtimeConfig).isPresent()) {
                leadAgent.getCompiledGraph().updateState(runtimeConfig, leadAgentProjectionState(snapshot));
                return;
            }
            runtimeCheckpointService.leadAgentSaver().put(
                    runtimeConfig,
                    Checkpoint.builder()
                            .id(UUID.randomUUID().toString())
                            .state(leadAgentProjectionState(snapshot))
                            .nodeId(LEAD_AGENT_START_NODE)
                            .nextNodeId(LEAD_AGENT_MODEL_NODE)
                            .build()
            );
        }
        catch (Exception exception) {
            throw new IllegalStateException("Failed to persist lead agent checkpoint state for " + snapshot.threadId(), exception);
        }
    }

    /**
     * 从 lead agent checkpoint 回填线程展示态。
     */
    private Optional<ThreadStateSnapshot> loadSnapshotFromCheckpoint(String threadId) {
        RunnableConfig runtimeConfig = RunnableConfig.builder().threadId(threadId).build();
        Optional<StateSnapshot> stateSnapshot = runtimeLeadAgent(threadId, "state")
                .getCompiledGraph()
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
            persistLeadAgentAuxiliaryState(
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
        clearLeadAgentAuxiliaryState(threadId, RuntimeStateKeys.PENDING_APPROVAL);
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
            persistLeadAgentAuxiliaryState(
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

    private RuntimeRunOptions resolveRunOptions(RequestedRuntimeRunOptions requestedRunOptions) {
        RequestedRuntimeRunOptions requestedOptions = requestedRunOptions == null
                ? RequestedRuntimeRunOptions.empty()
                : requestedRunOptions;

        if (hasText(requestedOptions.reasoningEffort())) {
            throw new InvalidRunOptionException("reasoningEffort is not supported yet in the current Java runtime. Please omit it for now.");
        }
        if (hasText(requestedOptions.agentName())) {
            throw new InvalidRunOptionException("agentName is not supported yet in the current Java runtime. Please omit it for now.");
        }

        String modelName = normalizeOptionalUserId(requestedOptions.modelName());
        if (modelName != null) {
            validateRequestedModel(modelName);
        }

        boolean planModeEnabled = requestedOptions.isPlanMode() == null || requestedOptions.isPlanMode();
        boolean subagentEnabled = requestedOptions.subagentEnabled() == null || requestedOptions.subagentEnabled();
        int maxConcurrentSubagents = requestedOptions.maxConcurrentSubagents() == null
                ? RuntimeRunOptions.DEFAULT_MAX_CONCURRENT_SUBAGENTS
                : requestedOptions.maxConcurrentSubagents();
        if (maxConcurrentSubagents < 1) {
            throw new InvalidRunOptionException("maxConcurrentSubagents must be greater than 0.");
        }
        if (maxConcurrentSubagents > 8) {
            throw new InvalidRunOptionException("maxConcurrentSubagents must not be greater than 8 in the current runtime.");
        }

        return new RuntimeRunOptions(modelName, planModeEnabled, subagentEnabled, maxConcurrentSubagents);
    }

    private RuntimeRunOptions effectiveRunOptions(RuntimeRunOptions runOptions) {
        return runOptions == null ? RuntimeRunOptions.defaults() : runOptions;
    }

    private DefaultToolCallingChatOptions chatOptionsFor(RuntimeRunOptions runOptions) {
        RuntimeRunOptions effectiveRunOptions = effectiveRunOptions(runOptions);
        if (!hasText(effectiveRunOptions.modelName())) {
            return null;
        }

        DefaultToolCallingChatOptions chatOptions = new DefaultToolCallingChatOptions();
        chatOptions.setModel(effectiveRunOptions.modelName());
        return chatOptions;
    }

    private void validateRequestedModel(String modelName) {
        if (chatModel instanceof FallbackChatModelConfiguration.FallbackChatModel) {
            throw new InvalidRunOptionException("modelName requires a real provider-backed runtimeChatModel, but the current runtime is using the fallback chat model.");
        }

        if (modelRegistryService == null) {
            return;
        }

        ModelDescriptor descriptor = modelRegistryService.listModels().stream()
                .filter(candidate -> candidate.id() != null && candidate.id().equals(modelName))
                .findFirst()
                .orElseThrow(() -> new InvalidRunOptionException("Unknown modelName: " + modelName));
        if (!descriptor.enabled()) {
            throw new InvalidRunOptionException("Requested model is disabled: " + modelName);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
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

    private Map<String, Object> leadAgentProjectionState(ThreadStateSnapshot snapshot) {
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
        return runtimeLeadAgent(threadId, "state").getCompiledGraph().stateOf(runtimeConfig);
    }

    private Optional<OverAllState> safeState(RunnableConfig runtimeConfig) {
        try {
            String threadId = runtimeConfig.threadId().orElse(null);
            if (threadId == null) {
                return Optional.empty();
            }
            return runtimeLeadAgent(threadId, "state")
                    .getCompiledGraph()
                    .stateOf(runtimeConfig)
                    .map(StateSnapshot::state);
        }
        catch (RuntimeException exception) {
            logger.debug("Failed to load current lead agent state for clarification handling: {}", runtimeConfig.threadId().orElse("unknown"), exception);
            return Optional.empty();
        }
    }

    private AgentClarificationRequestedException clarificationException(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof AgentClarificationRequestedException clarificationRequestedException) {
                return clarificationRequestedException;
            }
            cursor = cursor.getCause();
        }
        return null;
    }

    private List<ThreadMessage> clarificationMessages(OverAllState state,
                                                      ThreadStateSnapshot currentSnapshot,
                                                      String message,
                                                      String clarificationPrompt) {
        List<ThreadMessage> messages = messagesFromState(state);
        if (!messages.isEmpty()) {
            return messages;
        }

        List<ThreadMessage> fallbackMessages = new ArrayList<>(currentSnapshot.messages());
        fallbackMessages.add(new ThreadMessage("user", message));
        fallbackMessages.add(new ThreadMessage("assistant", clarificationPrompt));
        return List.copyOf(fallbackMessages);
    }

    private List<TodoItem> clarificationTodos(OverAllState state, ThreadStateSnapshot currentSnapshot) {
        List<TodoItem> todos = todosFromState(state);
        return todos.isEmpty() ? currentSnapshot.todos() : todos;
    }

    /**
     * 将非展示态辅助上下文写入 lead agent checkpoint。
     */
    private void persistLeadAgentAuxiliaryState(String threadId, Map<String, Object> updates) {
        var leadAgent = runtimeLeadAgent(threadId, "state");
        RunnableConfig runtimeConfig = RunnableConfig.builder().threadId(threadId).build();
        try {
            if (leadAgent.getCompiledGraph().stateOf(runtimeConfig).isPresent()) {
                leadAgent.getCompiledGraph().updateState(runtimeConfig, updates);
                return;
            }

            ThreadWorkspace workspace = threadWorkspaceService.getOrCreateWorkspace(threadId);
            Map<String, Object> initialState = new HashMap<>(leadAgentProjectionState(idleSnapshot(workspace)));
            initialState.putAll(updates);
            runtimeCheckpointService.leadAgentSaver().put(
                    runtimeConfig,
                    Checkpoint.builder()
                            .id(UUID.randomUUID().toString())
                            .state(initialState)
                            .nodeId(LEAD_AGENT_START_NODE)
                            .nextNodeId(LEAD_AGENT_MODEL_NODE)
                            .build()
            );
        }
        catch (Exception exception) {
            throw new IllegalStateException("Failed to persist lead agent auxiliary state for " + threadId, exception);
        }
    }

    private void clearLeadAgentAuxiliaryState(String threadId, String key) {
        Optional<StateSnapshot> stateSnapshot = stateSnapshot(threadId);
        if (stateSnapshot.isEmpty()) {
            return;
        }

        var leadAgent = runtimeLeadAgent(threadId, "state");
        RunnableConfig runtimeConfig = RunnableConfig.builder().threadId(threadId).build();
        try {
            leadAgent.getCompiledGraph().updateState(runtimeConfig, Map.of(key, OverAllState.MARK_FOR_REMOVAL));
        }
        catch (Exception exception) {
            throw new IllegalStateException("Failed to clear lead agent auxiliary state for " + threadId, exception);
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
