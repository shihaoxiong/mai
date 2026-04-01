package com.mai.deerflow.backend.runtime.memory;

/**
 * 结构化长期记忆中的用户画像部分。
 */
public record MemoryUserProfile(
        MemoryProfileSection workContext,
        MemoryProfileSection personalContext,
        MemoryProfileSection topOfMind
) {

    public MemoryUserProfile {
        workContext = workContext == null ? MemoryProfileSection.empty() : workContext;
        personalContext = personalContext == null ? MemoryProfileSection.empty() : personalContext;
        topOfMind = topOfMind == null ? MemoryProfileSection.empty() : topOfMind;
    }

    public static MemoryUserProfile empty() {
        return new MemoryUserProfile(
                MemoryProfileSection.empty(),
                MemoryProfileSection.empty(),
                MemoryProfileSection.empty()
        );
    }
}
