package com.mai.deerflow.backend.runtime.memory;

import java.util.Map;

/**
 * 长期记忆中的单条事实。
 *
 * 首版先覆盖用户事实、偏好与上下文片段，后续可在不破坏主结构的前提下继续扩展字段。
 */
public record MemoryFact(
        String memoryId,
        String category,
        String content,
        double confidence,
        String sourceThreadId,
        String createdAt,
        String updatedAt,
        Map<String, String> attributes
) {

    public MemoryFact {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        if (Double.isNaN(confidence) || confidence < 0.0d || confidence > 1.0d) {
            throw new IllegalArgumentException("confidence must be between 0.0 and 1.0");
        }

        category = category == null || category.isBlank() ? "general" : category.trim();
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
