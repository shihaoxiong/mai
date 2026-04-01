package com.mai.deerflow.backend.runtime.api;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * 发起线程运行请求体。
 */
public record ThreadRunRequest(
        String message,
        Boolean approvalRequired,
        String approvalReason,
        String userId,
        @JsonAlias("model_name") String modelName,
        @JsonAlias("reasoning_effort") String reasoningEffort,
        @JsonAlias("agent_name") String agentName,
        @JsonAlias("is_plan_mode") Boolean isPlanMode,
        @JsonAlias("subagent_enabled") Boolean subagentEnabled,
        @JsonAlias("max_concurrent_subagents") Integer maxConcurrentSubagents
) {

    public RequestedRuntimeRunOptions requestedRunOptions() {
        return new RequestedRuntimeRunOptions(
                modelName,
                reasoningEffort,
                agentName,
                isPlanMode,
                subagentEnabled,
                maxConcurrentSubagents
        );
    }
}
