package com.mai.deerflow.backend.runtime.subtask;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.Agent;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.ParallelAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mai.deerflow.backend.runtime.agent.LeadAgentDefinition;
import com.mai.deerflow.backend.runtime.agent.LeadAgentFactory;
import com.mai.deerflow.backend.runtime.contract.RunEventType;
import com.mai.deerflow.backend.runtime.event.ThreadEventService;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

@Service
/**
 * 应用内子任务执行器。
 *
 * 当前支持：
 * 1. 单 Agent 子任务
 * 2. 基于 `SequentialAgent` 的串行编排
 * 3. 基于 `ParallelAgent` 的并行编排
 * 4. 超时、取消、重试等基础生命周期管理
 */
public class SubTaskExecutor {

    private static final long DEFAULT_WAIT_TIMEOUT_MILLIS = 5_000L;
    private static final long DEFAULT_EXECUTION_TIMEOUT_MILLIS = 30_000L;

    private final ThreadWorkspaceService threadWorkspaceService;
    private final LeadAgentFactory leadAgentFactory;
    private final ChatModel chatModel;
    private final ThreadEventService threadEventService;
    private final ObjectMapper objectMapper;
    private final ExecutorService executionExecutor;
    private final ScheduledExecutorService timeoutScheduler;
    private final ConcurrentMap<String, RunningTaskHandle> runningTasks = new ConcurrentHashMap<>();

    @Autowired
    public SubTaskExecutor(ThreadWorkspaceService threadWorkspaceService,
                           LeadAgentFactory leadAgentFactory,
                           @Qualifier("runtimeChatModel") ChatModel chatModel,
                           ThreadEventService threadEventService,
                           ObjectMapper objectMapper) {
        this(
                threadWorkspaceService,
                leadAgentFactory,
                chatModel,
                threadEventService,
                objectMapper,
                Executors.newCachedThreadPool(daemonThreadFactory("subtask-exec-")),
                Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("subtask-timeout-"))
        );
    }

    /**
     * 允许测试替换基础执行器；会自动补一个默认的超时调度器。
     */
    public SubTaskExecutor(ThreadWorkspaceService threadWorkspaceService,
                           LeadAgentFactory leadAgentFactory,
                           ChatModel chatModel,
                           ThreadEventService threadEventService,
                           ObjectMapper objectMapper,
                           Executor executor) {
        this(
                threadWorkspaceService,
                leadAgentFactory,
                chatModel,
                threadEventService,
                objectMapper,
                toExecutorService(executor),
                Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("subtask-timeout-"))
        );
    }

    /**
     * 允许测试替换执行线程池和超时调度器。
     */
    public SubTaskExecutor(ThreadWorkspaceService threadWorkspaceService,
                           LeadAgentFactory leadAgentFactory,
                           ChatModel chatModel,
                           ThreadEventService threadEventService,
                           ObjectMapper objectMapper,
                           ExecutorService executionExecutor,
                           ScheduledExecutorService timeoutScheduler) {
        this.threadWorkspaceService = threadWorkspaceService;
        this.leadAgentFactory = leadAgentFactory;
        this.chatModel = chatModel;
        this.threadEventService = threadEventService;
        this.objectMapper = objectMapper;
        this.executionExecutor = executionExecutor;
        this.timeoutScheduler = timeoutScheduler;
    }

    /**
     * 为某次主线程 run 构造 `task` 工具。
     */
    public ToolCallback taskTool(String threadId, String runId) {
        return FunctionToolCallback
                .builder("task", (SubTaskToolRequest request) -> handleToolCall(threadId, runId, request))
                .description("Manage delegated subtasks. action=submit/status/cancel/retry, mode=single/sequential/parallel.")
                .inputType(SubTaskToolRequest.class)
                .build();
    }

    /**
     * 提交单 Agent 子任务。
     */
    public SubTaskRecord submit(String threadId, String runId, String title, String instruction) {
        return submit(threadId, runId, title, instruction, SubTaskOrchestrationMode.SINGLE, List.of(), null, 0);
    }

    /**
     * 提交指定编排模式的子任务。
     */
    public SubTaskRecord submit(String threadId,
                                String runId,
                                String title,
                                String instruction,
                                SubTaskOrchestrationMode mode,
                                List<SubTaskStep> steps) {
        return submit(threadId, runId, title, instruction, mode, steps, null, 0);
    }

    /**
     * 提交指定编排模式的子任务，并记录超时与重试信息。
     */
    public SubTaskRecord submit(String threadId,
                                String runId,
                                String title,
                                String instruction,
                                SubTaskOrchestrationMode mode,
                                List<SubTaskStep> steps,
                                Long timeoutMillis,
                                int retryCount) {
        String normalizedThreadId = requireText(threadId, "threadId");
        String normalizedRunId = requireText(runId, "runId");
        String normalizedInstruction = requireText(instruction, "instruction");
        String normalizedTitle = hasText(title) ? title.trim() : abbreviate(normalizedInstruction, 48);
        SubTaskOrchestrationMode normalizedMode = mode == null ? SubTaskOrchestrationMode.SINGLE : mode;
        List<SubTaskStep> normalizedSteps = normalizeSteps(steps);
        long effectiveTimeoutMillis = timeoutMillis == null || timeoutMillis < 1
                ? DEFAULT_EXECUTION_TIMEOUT_MILLIS
                : timeoutMillis;

        return submitWithTaskId(
                UUID.randomUUID().toString(),
                normalizedThreadId,
                normalizedRunId,
                normalizedTitle,
                normalizedInstruction,
                normalizedMode,
                normalizedSteps,
                effectiveTimeoutMillis,
                retryCount
        );
    }

    /**
     * 查询单个子任务状态。
     */
    public Optional<SubTaskRecord> find(String threadId, String taskId) {
        if (!hasText(threadId) || !hasText(taskId)) {
            return Optional.empty();
        }
        if (!threadWorkspaceService.exists(threadId.trim())) {
            return Optional.empty();
        }

        Path taskFile = taskFile(threadId.trim(), taskId.trim());
        if (!Files.isRegularFile(taskFile)) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.readValue(taskFile.toFile(), SubTaskRecord.class));
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to load subtask " + taskId + " for thread " + threadId, exception);
        }
    }

    /**
     * 列出某个线程下的全部子任务。
     */
    public List<SubTaskRecord> list(String threadId) {
        if (!threadWorkspaceService.exists(threadId)) {
            return List.of();
        }

        Path subTaskDirectory = subTaskDirectory(threadId);
        if (!Files.isDirectory(subTaskDirectory)) {
            return List.of();
        }

        try (Stream<Path> files = Files.list(subTaskDirectory)) {
            return files.filter(Files::isRegularFile)
                    .sorted(Comparator.naturalOrder())
                    .map(this::readRecord)
                    .sorted(Comparator.comparing(SubTaskRecord::createdAt))
                    .toList();
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to list subtasks for thread " + threadId, exception);
        }
    }

    /**
     * 取消仍在运行中的子任务。
     */
    public SubTaskRecord cancel(String threadId, String taskId) {
        String normalizedThreadId = requireText(threadId, "threadId");
        String normalizedTaskId = requireText(taskId, "taskId");
        SubTaskRecord existingRecord = find(normalizedThreadId, normalizedTaskId)
                .orElseThrow(() -> new IllegalArgumentException("Subtask not found: " + normalizedTaskId));

        RunningTaskHandle handle = runningTasks.get(taskKey(normalizedThreadId, normalizedTaskId));
        if (handle == null) {
            return existingRecord;
        }
        if (!handle.forceTerminalStatus(SubTaskStatus.CANCELLED)) {
            return find(normalizedThreadId, normalizedTaskId).orElse(existingRecord);
        }

        SubTaskRecord cancelledRecord = withStatus(
                existingRecord,
                SubTaskStatus.CANCELLED,
                null,
                "Subtask cancelled by request"
        );
        persistRecord(cancelledRecord);
        emitSubTaskUpdate(cancelledRecord);
        handle.cancelTimersAndExecution();
        return cancelledRecord;
    }

    /**
     * 重试失败、超时或已取消的子任务。
     */
    public SubTaskRecord retry(String threadId, String taskId, Long timeoutMillis) {
        String normalizedThreadId = requireText(threadId, "threadId");
        String normalizedTaskId = requireText(taskId, "taskId");
        SubTaskRecord existingRecord = find(normalizedThreadId, normalizedTaskId)
                .orElseThrow(() -> new IllegalArgumentException("Subtask not found: " + normalizedTaskId));

        if (existingRecord.status() == SubTaskStatus.RUNNING || existingRecord.status() == SubTaskStatus.PENDING) {
            throw new IllegalStateException("Subtask is still running: " + normalizedTaskId);
        }

        long effectiveTimeoutMillis = timeoutMillis == null || timeoutMillis < 1
                ? existingRecord.timeoutMillis()
                : timeoutMillis;

        return submitWithTaskId(
                normalizedTaskId,
                existingRecord.parentThreadId(),
                existingRecord.parentRunId(),
                existingRecord.title(),
                existingRecord.instruction(),
                parseMode(existingRecord.mode()),
                existingRecord.steps(),
                effectiveTimeoutMillis,
                existingRecord.retryCount() + 1
        );
    }

    private SubTaskRecord handleToolCall(String threadId, String runId, SubTaskToolRequest request) {
        String action = normalizeAction(request == null ? null : request.action());
        if ("status".equals(action)) {
            String taskId = requireText(request == null ? null : request.taskId(), "taskId");
            return find(threadId, taskId)
                    .orElseThrow(() -> new IllegalArgumentException("Subtask not found: " + taskId));
        }
        if ("cancel".equals(action)) {
            String taskId = requireText(request == null ? null : request.taskId(), "taskId");
            return cancel(threadId, taskId);
        }
        if ("retry".equals(action)) {
            String taskId = requireText(request == null ? null : request.taskId(), "taskId");
            SubTaskRecord retriedRecord = retry(threadId, taskId, request == null ? null : request.timeoutMillis());
            boolean waitForCompletion = request != null && Boolean.TRUE.equals(request.waitForCompletion());
            if (!waitForCompletion) {
                return retriedRecord;
            }
            return awaitCompletion(threadId, retriedRecord.taskId(), request.timeoutMillis());
        }

        SubTaskRecord submittedRecord = submit(
                threadId,
                runId,
                request == null ? null : request.title(),
                request == null ? null : request.prompt(),
                parseMode(request == null ? null : request.mode()),
                request == null ? null : request.steps(),
                request == null ? null : request.timeoutMillis(),
                0
        );
        boolean waitForCompletion = request != null && Boolean.TRUE.equals(request.waitForCompletion());
        if (!waitForCompletion) {
            return submittedRecord;
        }
        return awaitCompletion(threadId, submittedRecord.taskId(), request.timeoutMillis());
    }

    private SubTaskRecord submitWithTaskId(String taskId,
                                           String threadId,
                                           String runId,
                                           String title,
                                           String instruction,
                                           SubTaskOrchestrationMode mode,
                                           List<SubTaskStep> steps,
                                           long timeoutMillis,
                                           int retryCount) {
        String timestamp = Instant.now().toString();
        SubTaskRecord pendingRecord = new SubTaskRecord(
                taskId,
                threadId,
                runId,
                title,
                instruction,
                mode.name(),
                steps,
                timeoutMillis,
                retryCount,
                SubTaskStatus.PENDING,
                null,
                null,
                timestamp,
                timestamp
        );
        persistRecord(pendingRecord);

        RunningTaskHandle handle = new RunningTaskHandle();
        runningTasks.put(taskKey(threadId, taskId), handle);

        CompletableFuture<SubTaskRecord> resultFuture = new CompletableFuture<>();
        Future<?> executionFuture = executionExecutor.submit(() -> {
            try {
                resultFuture.complete(executeSubTask(pendingRecord, handle));
            }
            catch (Throwable throwable) {
                resultFuture.completeExceptionally(throwable);
            }
            finally {
                runningTasks.remove(taskKey(threadId, taskId), handle);
            }
        });

        handle.executionFuture(executionFuture);
        handle.resultFuture(resultFuture);
        handle.timeoutFuture(timeoutScheduler.schedule(
                () -> markTimedOut(pendingRecord, handle),
                timeoutMillis,
                TimeUnit.MILLISECONDS
        ));
        return pendingRecord;
    }

    private SubTaskRecord awaitCompletion(String threadId, String taskId, Long timeoutMillis) {
        RunningTaskHandle handle = runningTasks.get(taskKey(threadId, taskId));
        if (handle == null || handle.resultFuture() == null) {
            return find(threadId, taskId)
                    .orElseThrow(() -> new IllegalArgumentException("Subtask not found: " + taskId));
        }

        long effectiveTimeout = timeoutMillis == null || timeoutMillis < 1
                ? DEFAULT_WAIT_TIMEOUT_MILLIS
                : timeoutMillis;

        try {
            return handle.resultFuture().get(effectiveTimeout, TimeUnit.MILLISECONDS);
        }
        catch (Exception exception) {
            return find(threadId, taskId)
                    .orElseThrow(() -> new IllegalArgumentException("Subtask not found: " + taskId));
        }
    }

    private SubTaskRecord executeSubTask(SubTaskRecord pendingRecord, RunningTaskHandle handle) {
        SubTaskRecord runningRecord = withStatus(pendingRecord, SubTaskStatus.RUNNING, null, null);
        persistRecord(runningRecord);
        threadEventService.emit(
                runningRecord.parentThreadId(),
                runningRecord.parentRunId(),
                RunEventType.SUBTASK_STARTED,
                Map.of(
                        "taskId", runningRecord.taskId(),
                        "title", runningRecord.title(),
                        "status", runningRecord.status().name(),
                        "mode", runningRecord.mode()
                )
        );

        try {
            String result = switch (parseMode(runningRecord.mode())) {
                case SINGLE -> executeSingleAgentSubTask(runningRecord);
                case SEQUENTIAL -> executeSequentialSubTask(runningRecord);
                case PARALLEL -> executeParallelSubTask(runningRecord);
            };

            if (handle.forcedTerminalStatus() != null) {
                return find(runningRecord.parentThreadId(), runningRecord.taskId()).orElse(runningRecord);
            }

            SubTaskRecord completedRecord = withStatus(
                    runningRecord,
                    SubTaskStatus.COMPLETED,
                    result,
                    null
            );
            persistRecord(completedRecord);
            emitSubTaskUpdate(completedRecord);
            return completedRecord;
        }
        catch (Exception exception) {
            if (handle.forcedTerminalStatus() != null) {
                return find(runningRecord.parentThreadId(), runningRecord.taskId()).orElse(runningRecord);
            }

            SubTaskRecord failedRecord = withStatus(
                    runningRecord,
                    SubTaskStatus.FAILED,
                    null,
                    String.valueOf(exception.getMessage())
            );
            persistRecord(failedRecord);
            emitSubTaskUpdate(failedRecord);
            return failedRecord;
        }
    }

    private SubTaskRecord withStatus(SubTaskRecord baseRecord,
                                     SubTaskStatus status,
                                     String result,
                                     String errorMessage) {
        return new SubTaskRecord(
                baseRecord.taskId(),
                baseRecord.parentThreadId(),
                baseRecord.parentRunId(),
                baseRecord.title(),
                baseRecord.instruction(),
                baseRecord.mode(),
                baseRecord.steps(),
                baseRecord.timeoutMillis(),
                baseRecord.retryCount(),
                status,
                result,
                errorMessage,
                baseRecord.createdAt(),
                Instant.now().toString()
        );
    }

    private void emitSubTaskUpdate(SubTaskRecord record) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", record.taskId());
        payload.put("status", record.status().name());
        payload.put("mode", record.mode());
        payload.put("retryCount", record.retryCount());
        if (hasText(record.result())) {
            payload.put("result", record.result());
        }
        if (hasText(record.errorMessage())) {
            payload.put("errorMessage", record.errorMessage());
        }
        threadEventService.emit(
                record.parentThreadId(),
                record.parentRunId(),
                RunEventType.SUBTASK_UPDATED,
                payload
        );
    }

    private void markTimedOut(SubTaskRecord record, RunningTaskHandle handle) {
        if (!handle.forceTerminalStatus(SubTaskStatus.TIMED_OUT)) {
            return;
        }

        SubTaskRecord timedOutRecord = withStatus(
                record,
                SubTaskStatus.TIMED_OUT,
                null,
                "Subtask execution timed out after %d ms".formatted(record.timeoutMillis())
        );
        persistRecord(timedOutRecord);
        emitSubTaskUpdate(timedOutRecord);
        handle.cancelTimersAndExecution();
    }

    private void persistRecord(SubTaskRecord record) {
        Path taskFile = taskFile(record.parentThreadId(), record.taskId());
        Path tempFile = taskFile.resolveSibling(taskFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(taskFile.getParent());
            objectMapper.writeValue(tempFile.toFile(), record);
            try {
                Files.move(tempFile, taskFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (IOException ignored) {
                Files.move(tempFile, taskFile, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to persist subtask " + record.taskId(), exception);
        }
    }

    private SubTaskRecord readRecord(Path file) {
        try {
            return objectMapper.readValue(file.toFile(), SubTaskRecord.class);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to read subtask file " + file, exception);
        }
    }

    private Path subTaskDirectory(String threadId) {
        return threadWorkspaceService.getOrCreateWorkspace(threadId)
                .threadRoot()
                .resolve("metadata")
                .resolve("subtasks");
    }

    private Path taskFile(String threadId, String taskId) {
        return subTaskDirectory(threadId).resolve(taskId + ".json");
    }

    private String subTaskThreadId(String threadId, String taskId) {
        return "%s-subtask-%s".formatted(threadId, taskId);
    }

    private String taskKey(String threadId, String taskId) {
        return threadId + "::" + taskId;
    }

    private String executeSingleAgentSubTask(SubTaskRecord record) throws Exception {
        ReactAgent subTaskAgent = leadAgentFactory.create(LeadAgentDefinition.builder(chatModel)
                .name("runtime-subtask-agent")
                .instruction("You are a delegated subtask agent. Complete only the delegated task and return a concise useful result.")
                .saver(new MemorySaver())
                .releaseThread(false)
                .build());

        AssistantMessage assistantMessage = subTaskAgent.call(
                subTaskPrompt(record),
                RunnableConfig.builder()
                        .threadId(subTaskThreadId(record.parentThreadId(), record.taskId()))
                        .build()
        );
        return assistantMessage.getText();
    }

    private String executeSequentialSubTask(SubTaskRecord record) throws Exception {
        List<SubTaskStep> steps = requireConfiguredSteps(record);
        List<Agent> subAgents = buildFlowSubAgents(steps, "sequential-step");
        SequentialAgent sequentialAgent = SequentialAgent.builder()
                .name("runtime-sequential-subtask")
                .description("Sequential multi-agent subtask orchestrator")
                .subAgents(subAgents)
                .executor(executionExecutor)
                .build();

        String lastStepKey = outputKeyForStep(normalizeStepName(steps.get(steps.size() - 1).name()));
        String lastStepResult = sequentialAgent.invokeAndGetOutput(
                        subTaskPrompt(record),
                        RunnableConfig.builder()
                                .threadId(subTaskThreadId(record.parentThreadId(), record.taskId()) + "-seq")
                                .build()
                )
                .map(output -> readStateText(output.state(), lastStepKey))
                .filter(this::hasText)
                .orElse(null);
        if (hasText(lastStepResult)) {
            return lastStepResult;
        }

        return sequentialAgent.invoke(
                        subTaskPrompt(record),
                        RunnableConfig.builder()
                                .threadId(subTaskThreadId(record.parentThreadId(), record.taskId()) + "-seq-fallback")
                                .build()
                )
                .map(state -> collectStepOutputs(state, steps))
                .filter(this::hasText)
                .orElseThrow(() -> new IllegalStateException("Sequential agent returned no state"));
    }

    private String executeParallelSubTask(SubTaskRecord record) throws Exception {
        List<SubTaskStep> steps = requireConfiguredSteps(record);
        List<Agent> subAgents = buildFlowSubAgents(steps, "parallel-step");
        ParallelAgent parallelAgent = ParallelAgent.builder()
                .name("runtime-parallel-subtask")
                .description("Parallel multi-agent subtask orchestrator")
                .subAgents(subAgents)
                .mergeOutputKey("parallelMerged")
                .mergeStrategy(new ParallelAgent.ConcatenationMergeStrategy("\n"))
                .maxConcurrency(steps.size())
                .executor(executionExecutor)
                .build();

        return parallelAgent.invoke(
                        subTaskPrompt(record),
                        RunnableConfig.builder()
                                .threadId(subTaskThreadId(record.parentThreadId(), record.taskId()) + "-par")
                                .build()
                )
                .map(state -> state.value("parallelMerged", String.class)
                        .orElseGet(() -> collectStepOutputs(state, steps)))
                .orElseThrow(() -> new IllegalStateException("Parallel agent returned no state"));
    }

    private List<Agent> buildFlowSubAgents(List<SubTaskStep> steps, String namePrefix) {
        return steps.stream()
                .<Agent>map(step -> {
                    String stepName = normalizeStepName(step.name());
                    ReactAgent subAgent = leadAgentFactory.create(LeadAgentDefinition.builder(chatModel)
                            .name(namePrefix + "-" + stepName)
                            .instruction("""
                                    You are a delegated subtask worker.
                                    Step name: %s
                                    Step instruction: %s
                                    Use the incoming user message as the shared task context, and produce a concise result for this step.
                                    """.formatted(
                                    stepName,
                                    step.prompt().trim()
                            ))
                            .saver(new MemorySaver())
                            .releaseThread(false)
                            .build());
                    subAgent.setOutputKey(outputKeyForStep(stepName));
                    return subAgent;
                })
                .toList();
    }

    private String collectStepOutputs(com.alibaba.cloud.ai.graph.OverAllState state, List<SubTaskStep> steps) {
        return steps.stream()
                .map(step -> readStateText(state, outputKeyForStep(normalizeStepName(step.name()))))
                .filter(this::hasText)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String readStateText(com.alibaba.cloud.ai.graph.OverAllState state, String key) {
        Object value = state.value(key).orElse(null);
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Message message) {
            return message.getText();
        }
        if (value instanceof AssistantMessage assistantMessage) {
            return assistantMessage.getText();
        }
        return value == null ? null : String.valueOf(value);
    }

    private List<SubTaskStep> requireConfiguredSteps(SubTaskRecord record) {
        List<SubTaskStep> steps = normalizeSteps(record.steps());
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("steps must not be empty for mode " + record.mode());
        }
        return steps;
    }

    private List<SubTaskStep> normalizeSteps(List<SubTaskStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        return java.util.stream.IntStream.range(0, steps.size())
                .mapToObj(index -> {
                    SubTaskStep step = steps.get(index);
                    String prompt = requireText(step == null ? null : step.prompt(), "steps[%d].prompt".formatted(index));
                    String stepName = hasText(step.name()) ? step.name().trim() : "step-" + (index + 1);
                    return new SubTaskStep(stepName, prompt);
                })
                .toList();
    }

    private SubTaskOrchestrationMode parseMode(String mode) {
        if (!hasText(mode)) {
            return SubTaskOrchestrationMode.SINGLE;
        }
        return SubTaskOrchestrationMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
    }

    private String normalizeAction(String action) {
        if (!hasText(action)) {
            return "submit";
        }
        return action.trim().toLowerCase(Locale.ROOT);
    }

    private String subTaskPrompt(SubTaskRecord record) {
        return """
                Delegated subtask
                Parent thread: %s
                Subtask title: %s
                Mode: %s
                Instruction:
                %s
                """.formatted(
                record.parentThreadId(),
                record.title(),
                record.mode(),
                record.instruction()
        );
    }

    private String normalizeStepName(String name) {
        return hasText(name) ? name.trim() : "step";
    }

    private String outputKeyForStep(String stepName) {
        return "subtask_" + stepName.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private String requireText(String value, String fieldName) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String abbreviate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static java.util.concurrent.ThreadFactory daemonThreadFactory(String prefix) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + UUID.randomUUID());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static ExecutorService toExecutorService(Executor executor) {
        if (executor instanceof ExecutorService executorService) {
            return executorService;
        }
        return new DelegatingExecutorService(executor);
    }

    private static final class RunningTaskHandle {

        private final AtomicReference<SubTaskStatus> forcedTerminalStatus = new AtomicReference<>();
        private volatile Future<?> executionFuture;
        private volatile ScheduledFuture<?> timeoutFuture;
        private volatile CompletableFuture<SubTaskRecord> resultFuture;

        private boolean forceTerminalStatus(SubTaskStatus status) {
            return forcedTerminalStatus.compareAndSet(null, status);
        }

        private SubTaskStatus forcedTerminalStatus() {
            return forcedTerminalStatus.get();
        }

        private void executionFuture(Future<?> executionFuture) {
            this.executionFuture = executionFuture;
        }

        private void timeoutFuture(ScheduledFuture<?> timeoutFuture) {
            this.timeoutFuture = timeoutFuture;
        }

        private void resultFuture(CompletableFuture<SubTaskRecord> resultFuture) {
            this.resultFuture = resultFuture;
        }

        private CompletableFuture<SubTaskRecord> resultFuture() {
            return resultFuture;
        }

        private void cancelTimersAndExecution() {
            ScheduledFuture<?> timeout = timeoutFuture;
            if (timeout != null) {
                timeout.cancel(false);
            }
            Future<?> future = executionFuture;
            if (future != null) {
                future.cancel(true);
            }
        }
    }

    private static final class DelegatingExecutorService extends AbstractExecutorService {

        private final Executor delegate;
        private volatile boolean shutdown;

        private DelegatingExecutorService(Executor delegate) {
            this.delegate = delegate;
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return new ArrayList<>();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(command);
        }
    }
}
