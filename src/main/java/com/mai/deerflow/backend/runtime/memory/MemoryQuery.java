package com.mai.deerflow.backend.runtime.memory;

/**
 * 长期记忆读取时使用的筛选条件。
 */
public record MemoryQuery(
        Integer limit,
        Double minConfidence
) {

    public MemoryQuery {
        if (limit != null && limit < 1) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }
        if (minConfidence != null && (Double.isNaN(minConfidence) || minConfidence < 0.0d || minConfidence > 1.0d)) {
            throw new IllegalArgumentException("minConfidence must be between 0.0 and 1.0");
        }
    }

    public static MemoryQuery all() {
        return new MemoryQuery(null, null);
    }
}
