package com.mai.deerflow.backend.runtime.contract;

/**
 * 供前端展示和恢复使用的待办项快照。
 */
public record TodoItem(
        String id,
        String title,
        TodoStatus status
) {
}
