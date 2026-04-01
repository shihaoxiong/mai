package com.mai.deerflow.backend.runtime.memory;

import java.util.List;

/**
 * 长期记忆注入后的结果。
 */
public record MemoryInjectionResult(
        String effectiveUserInput,
        List<MemoryFact> memoryFacts
) {

    public MemoryInjectionResult {
        memoryFacts = memoryFacts == null ? List.of() : List.copyOf(memoryFacts);
    }

    public boolean hasMemories() {
        return !memoryFacts.isEmpty();
    }
}
