package com.mai.deerflow.backend.runtime.subtask;

/**
 * `task` 工具的统一输入结构。
 *
 * action:
 * - `submit`：提交子任务
 * - `status`：查询已有子任务状态
 */
public record SubTaskToolRequest(
        String action,
        String taskId,
        String title,
        String prompt,
        String mode,
        java.util.List<SubTaskStep> steps,
        Boolean waitForCompletion,
        Long timeoutMillis
) {
}
