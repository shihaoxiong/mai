package com.mai.deerflow.backend.runtime.graph;

/**
 * Lead agent state 中使用的核心 key 常量。
 *
 * 统一集中定义，便于运行时服务和测试共享同一套命名。
 */
public final class RuntimeStateKeys {

    public static final String THREAD_ID = "threadId";
    public static final String RUN_ID = "runId";
    public static final String APPROVAL = "approval";
    public static final String PENDING_APPROVAL = "pendingApproval";
    public static final String THREAD_CONTEXT = "threadContext";
    public static final String TODOS = "todos";
    public static final String RUN_STATUS = "runStatus";
    public static final String TITLE = "title";
    public static final String SUGGESTIONS = "suggestions";

    private RuntimeStateKeys() {
    }
}
