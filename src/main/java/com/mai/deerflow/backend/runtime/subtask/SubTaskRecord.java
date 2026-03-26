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
        SubTaskStatus status,
        String result,
        String errorMessage,
        String createdAt,
        String updatedAt
) {
}
