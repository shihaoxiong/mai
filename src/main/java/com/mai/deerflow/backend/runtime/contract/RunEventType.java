package com.mai.deerflow.backend.runtime.contract;

public enum RunEventType {
    RUN_STARTED("run.started"),
    TOKEN_DELTA("token.delta"),
    TOOL_CALL_STARTED("tool.call.started"),
    TOOL_CALL_COMPLETED("tool.call.completed"),
    SUBTASK_STARTED("subtask.started"),
    SUBTASK_UPDATED("subtask.updated"),
    APPROVAL_REQUIRED("approval.required"),
    ARTIFACT_CREATED("artifact.created"),
    MEMORY_SCHEDULED("memory.scheduled"),
    RUN_COMPLETED("run.completed"),
    RUN_FAILED("run.failed");

    private final String wireName;

    RunEventType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
