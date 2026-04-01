package com.mai.deerflow.backend.runtime.api;

/**
 * 单次 run 请求里携带的原始运行参数。
 *
 * 该对象保留“用户请求的值”，由运行时服务进一步校验并解析成真正可执行的 `RuntimeRunOptions`。
 */
public record RequestedRuntimeRunOptions(
        String modelName,
        String reasoningEffort,
        String agentName,
        Boolean isPlanMode,
        Boolean subagentEnabled,
        Integer maxConcurrentSubagents
) {

    public static RequestedRuntimeRunOptions empty() {
        return new RequestedRuntimeRunOptions(null, null, null, null, null, null);
    }
}
