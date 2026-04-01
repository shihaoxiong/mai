package com.mai.deerflow.backend.runtime.memory;

/**
 * 结构化长期记忆中的历史摘要部分。
 */
public record MemoryHistoryProfile(
        MemoryProfileSection recentMonths,
        MemoryProfileSection earlierContext,
        MemoryProfileSection longTermBackground
) {

    public MemoryHistoryProfile {
        recentMonths = recentMonths == null ? MemoryProfileSection.empty() : recentMonths;
        earlierContext = earlierContext == null ? MemoryProfileSection.empty() : earlierContext;
        longTermBackground = longTermBackground == null ? MemoryProfileSection.empty() : longTermBackground;
    }

    public static MemoryHistoryProfile empty() {
        return new MemoryHistoryProfile(
                MemoryProfileSection.empty(),
                MemoryProfileSection.empty(),
                MemoryProfileSection.empty()
        );
    }
}
