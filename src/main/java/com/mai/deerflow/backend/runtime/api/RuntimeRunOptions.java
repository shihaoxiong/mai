package com.mai.deerflow.backend.runtime.api;

/**
 * lead agent 当前一次 run 真正生效的运行参数快照。
 */
public record RuntimeRunOptions(
        String modelName,
        boolean planModeEnabled,
        boolean subagentEnabled,
        int maxConcurrentSubagents
) {

    public static final int DEFAULT_MAX_CONCURRENT_SUBAGENTS = 3;

    public static RuntimeRunOptions defaults() {
        return new RuntimeRunOptions(null, true, true, DEFAULT_MAX_CONCURRENT_SUBAGENTS);
    }
}
