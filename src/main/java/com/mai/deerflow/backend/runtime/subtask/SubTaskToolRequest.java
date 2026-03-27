package com.mai.deerflow.backend.runtime.subtask;

/**
 * `task` 工具的统一输入结构。
 *
 * action:
 * - `submit`：提交子任务
 * - `status`：查询已有子任务状态
 * - `cancel`：取消运行中的子任务
 * - `retry`：重试失败、超时或已取消的子任务
 */
public record SubTaskToolRequest(
        String action,
        String taskId,
        String title,
        String prompt,
        String mode,
        java.util.List<SubTaskStep> steps,
        Boolean waitForCompletion,
        Long timeoutMillis,
        Boolean retry
) {
}
