package com.mai.deerflow.backend.runtime.contract;

public record TodoItem(
        String id,
        String title,
        TodoStatus status
) {
}
