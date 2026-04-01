package com.mai.deerflow.backend.runtime.subtask;

/**
 * 多 Agent 子任务中的单个步骤定义。
 */
public record SubTaskStep(
        String name,
        String prompt
) {
}
