package com.mai.deerflow.backend.runtime.contract;

/**
 * 计划模式下待办项的执行状态。
 */
public enum TodoStatus {
    /** 任务尚未开始。 */
    PENDING,
    /** 任务正在执行。 */
    IN_PROGRESS,
    /** 任务已经完成。 */
    COMPLETED,
    /** 任务被外部条件阻塞。 */
    BLOCKED
}
