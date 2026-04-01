package com.mai.deerflow.backend.runtime.subtask;

/**
 * 子任务生命周期状态。
 */
public enum SubTaskStatus {
    PENDING,
    RUNNING,
    CANCELLED,
    TIMED_OUT,
    COMPLETED,
    FAILED
}
