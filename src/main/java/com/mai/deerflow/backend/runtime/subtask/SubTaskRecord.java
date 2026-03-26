package com.mai.deerflow.backend.runtime.subtask;

/**
 * 子任务在平台层跟踪和持久化的快照。
 */
public record SubTaskRecord(
        String taskId,
        String parentThreadId,
        String parentRunId,
        String title,
        String instruction,
        String mode,
        java.util.List<SubTaskStep> steps,
        SubTaskStatus status,
        String result,
        String errorMessage,
        String createdAt,
        String updatedAt
) {

    public SubTaskRecord {
        steps = steps == null ? java.util.List.of() : java.util.List.copyOf(steps);
    }
}
