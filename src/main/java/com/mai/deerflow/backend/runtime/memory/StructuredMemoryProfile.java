package com.mai.deerflow.backend.runtime.memory;

/**
 * 更接近 DeerFlow 的结构化长期记忆档案。
 */
public record StructuredMemoryProfile(
        String version,
        String lastUpdated,
        MemoryUserProfile user,
        MemoryHistoryProfile history
) {

    public StructuredMemoryProfile {
        version = version == null || version.isBlank() ? "1.0" : version.trim();
        lastUpdated = lastUpdated == null ? "" : lastUpdated;
        user = user == null ? MemoryUserProfile.empty() : user;
        history = history == null ? MemoryHistoryProfile.empty() : history;
    }

    public static StructuredMemoryProfile empty() {
        return new StructuredMemoryProfile(
                "1.0",
                "",
                MemoryUserProfile.empty(),
                MemoryHistoryProfile.empty()
        );
    }

    public boolean hasContent() {
        return user.workContext().hasSummary()
                || user.personalContext().hasSummary()
                || user.topOfMind().hasSummary()
                || history.recentMonths().hasSummary()
                || history.earlierContext().hasSummary()
                || history.longTermBackground().hasSummary();
    }
}
