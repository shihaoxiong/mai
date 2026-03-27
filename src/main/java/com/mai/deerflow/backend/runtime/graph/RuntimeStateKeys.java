package com.mai.deerflow.backend.runtime.graph;

/**
 * Runtime Graph 在 state 中使用的核心 key 常量。
 *
 * 统一集中定义，便于图节点、服务层和测试共享同一套命名。
 */
public final class RuntimeStateKeys {

    public static final String THREAD_ID = "threadId";
    public static final String RUN_ID = "runId";
    public static final String USER_ID = "userId";
    public static final String USER_INPUT = "userInput";
    public static final String AGENT_INPUT = "agentInput";
    public static final String ASSISTANT_OUTPUT = "assistantOutput";
    public static final String LEAD_THREAD_STATE = "leadThreadState";
    public static final String APPROVAL = "approval";
    public static final String PENDING_APPROVAL = "pendingApproval";
    public static final String THREAD_CONTEXT = "threadContext";
    public static final String WORKSPACE = "workspace";
    public static final String UPLOADS = "uploads";
    public static final String MEMORY_CONTEXT = "memoryContext";
    public static final String TODOS = "todos";
    public static final String RUN_STATUS = "runStatus";
    public static final String ARTIFACTS = "artifacts";
    public static final String TITLE = "title";
    public static final String SUGGESTIONS = "suggestions";
    public static final String CONTEXT_READY = "contextReady";

    private RuntimeStateKeys() {
    }
}
