package com.mai.deerflow.backend.runtime.subtask;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mai.deerflow.backend.runtime.agent.LeadAgentDefinition;
import com.mai.deerflow.backend.runtime.agent.LeadAgentFactory;
import com.mai.deerflow.backend.runtime.contract.RunEventType;
import com.mai.deerflow.backend.runtime.event.ThreadEventService;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Service
/**
 * 应用内子任务执行器。
 *
 * P3-04 先实现“提交、状态跟踪、结果收集”的最小闭环，
 * 后续可在此基础上继续接入超时、取消、重试和多 agent 编排。
 */
public class SubTaskExecutor {

    private static final long DEFAULT_WAIT_TIMEOUT_MILLIS = 5_000L;

    private final ThreadWorkspaceService threadWorkspaceService;
    private final LeadAgentFactory leadAgentFactory;
    private final ChatModel chatModel;
    private final ThreadEventService threadEventService;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final ConcurrentMap<String, CompletableFuture<SubTaskRecord>> runningTasks = new ConcurrentHashMap<>();

    @Autowired
    public SubTaskExecutor(ThreadWorkspaceService threadWorkspaceService,
                           LeadAgentFactory leadAgentFactory,
                           ChatModel chatModel,
                           ThreadEventService threadEventService,
                           ObjectMapper objectMapper) {
        this(threadWorkspaceService, leadAgentFactory, chatModel, threadEventService, objectMapper, ForkJoinPool.commonPool());
    }

    /**
     * 允许测试或特定部署场景替换执行器。
     */
    public SubTaskExecutor(ThreadWorkspaceService threadWorkspaceService,
                           LeadAgentFactory leadAgentFactory,
                           ChatModel chatModel,
                           ThreadEventService threadEventService,
                           ObjectMapper objectMapper,
                           Executor executor) {
        this.threadWorkspaceService = threadWorkspaceService;
        this.leadAgentFactory = leadAgentFactory;
        this.chatModel = chatModel;
        this.threadEventService = threadEventService;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    /**
     * 为某次主线程 run 构造 `task` 工具。
     */
    public ToolCallback taskTool(String threadId, String runId) {
        return FunctionToolCallback
                .builder("task", (SubTaskToolRequest request) -> handleToolCall(threadId, runId, request))
                .description("Submit a delegated subtask or query its status. Use action=submit to create, action=status to fetch updates.")
                .inputType(SubTaskToolRequest.class)
                .build();
    }

    /**
     * 提交子任务并异步执行。
     */
    public SubTaskRecord submit(String threadId, String runId, String title, String instruction) {
        String normalizedThreadId = requireText(threadId, "threadId");
        String normalizedRunId = requireText(runId, "runId");
        String normalizedInstruction = requireText(instruction, "instruction");
        String normalizedTitle = hasText(title) ? title.trim() : abbreviate(normalizedInstruction, 48);
        String taskId = UUID.randomUUID().toString();
        String timestamp = Instant.now().toString();

        SubTaskRecord pendingRecord = new SubTaskRecord(
                taskId,
                normalizedThreadId,
                normalizedRunId,
                normalizedTitle,
                normalizedInstruction,
                SubTaskStatus.PENDING,
                null,
                null,
                timestamp,
                timestamp
        );
        persistRecord(pendingRecord);

        CompletableFuture<SubTaskRecord> future = CompletableFuture.supplyAsync(
                () -> executeSubTask(pendingRecord),
                executor
        );
        runningTasks.put(taskKey(normalizedThreadId, taskId), future);
        future.whenComplete((ignored, throwable) -> runningTasks.remove(taskKey(normalizedThreadId, taskId)));
        return pendingRecord;
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

    private SubTaskRecord handleToolCall(String threadId, String runId, SubTaskToolRequest request) {
        String action = normalizeAction(request == null ? null : request.action());
        if ("status".equals(action)) {
            String taskId = requireText(request == null ? null : request.taskId(), "taskId");
            return find(threadId, taskId)
                    .orElseThrow(() -> new IllegalArgumentException("Subtask not found: " + taskId));
        }

        SubTaskRecord submittedRecord = submit(
                threadId,
                runId,
                request == null ? null : request.title(),
                request == null ? null : request.prompt()
        );
        boolean waitForCompletion = request != null && Boolean.TRUE.equals(request.waitForCompletion());
        if (!waitForCompletion) {
            return submittedRecord;
        }
        return awaitCompletion(threadId, submittedRecord.taskId(), request.timeoutMillis());
    }

    private SubTaskRecord awaitCompletion(String threadId, String taskId, Long timeoutMillis) {
        CompletableFuture<SubTaskRecord> future = runningTasks.get(taskKey(threadId, taskId));
        if (future == null) {
            return find(threadId, taskId)
                    .orElseThrow(() -> new IllegalArgumentException("Subtask not found: " + taskId));
        }

        long effectiveTimeout = timeoutMillis == null || timeoutMillis < 1
                ? DEFAULT_WAIT_TIMEOUT_MILLIS
                : timeoutMillis;

        try {
            return future.get(effectiveTimeout, TimeUnit.MILLISECONDS);
        }
        catch (Exception exception) {
            return find(threadId, taskId)
                    .orElseThrow(() -> new IllegalArgumentException("Subtask not found: " + taskId));
        }
    }

    private SubTaskRecord executeSubTask(SubTaskRecord pendingRecord) {
        SubTaskRecord runningRecord = withStatus(pendingRecord, SubTaskStatus.RUNNING, null, null);
        persistRecord(runningRecord);
        threadEventService.emit(
                runningRecord.parentThreadId(),
                runningRecord.parentRunId(),
                RunEventType.SUBTASK_STARTED,
                Map.of(
                        "taskId", runningRecord.taskId(),
                        "title", runningRecord.title(),
                        "status", runningRecord.status().name()
                )
        );

        try {
            ReactAgent subTaskAgent = leadAgentFactory.create(LeadAgentDefinition.builder(chatModel)
                    .name("runtime-subtask-agent")
                    .instruction("You are a delegated subtask agent. Complete only the delegated task and return a concise useful result.")
                    .saver(new MemorySaver())
                    .releaseThread(false)
                    .build());

            AssistantMessage assistantMessage = subTaskAgent.call(
                    """
                    Delegated subtask
                    Parent thread: %s
                    Subtask title: %s
                    Instruction:
                    %s
                    """.formatted(
                            runningRecord.parentThreadId(),
                            runningRecord.title(),
                            runningRecord.instruction()
                    ),
                    RunnableConfig.builder()
                            .threadId(subTaskThreadId(runningRecord.parentThreadId(), runningRecord.taskId()))
                            .build()
            );

            SubTaskRecord completedRecord = withStatus(
                    runningRecord,
                    SubTaskStatus.COMPLETED,
                    assistantMessage.getText(),
                    null
            );
            persistRecord(completedRecord);
            threadEventService.emit(
                    completedRecord.parentThreadId(),
                    completedRecord.parentRunId(),
                    RunEventType.SUBTASK_UPDATED,
                    Map.of(
                            "taskId", completedRecord.taskId(),
                            "status", completedRecord.status().name(),
                            "result", completedRecord.result()
                    )
            );
            return completedRecord;
        }
        catch (Exception exception) {
            SubTaskRecord failedRecord = withStatus(
                    runningRecord,
                    SubTaskStatus.FAILED,
                    null,
                    String.valueOf(exception.getMessage())
            );
            persistRecord(failedRecord);
            threadEventService.emit(
                    failedRecord.parentThreadId(),
                    failedRecord.parentRunId(),
                    RunEventType.SUBTASK_UPDATED,
                    Map.of(
                            "taskId", failedRecord.taskId(),
                            "status", failedRecord.status().name(),
                            "errorMessage", failedRecord.errorMessage()
                    )
            );
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
                status,
                result,
                errorMessage,
                baseRecord.createdAt(),
                Instant.now().toString()
        );
    }

    private void persistRecord(SubTaskRecord record) {
        Path taskFile = taskFile(record.parentThreadId(), record.taskId());
        try {
            Files.createDirectories(taskFile.getParent());
            objectMapper.writeValue(taskFile.toFile(), record);
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

    private String normalizeAction(String action) {
        if (!hasText(action)) {
            return "submit";
        }
        return action.trim().toLowerCase(Locale.ROOT);
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
}
