package com.mai.deerflow.backend.runtime.memory;

/**
 * 结构化长期记忆中的单个摘要段落。
 */
public record MemoryProfileSection(
        String summary,
        String updatedAt
) {

    public static MemoryProfileSection empty() {
        return new MemoryProfileSection("", "");
    }

    public boolean hasSummary() {
        return summary != null && !summary.isBlank();
    }
}
