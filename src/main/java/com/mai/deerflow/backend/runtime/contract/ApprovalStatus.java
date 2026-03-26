package com.mai.deerflow.backend.runtime.contract;

/**
 * 审批任务本身的状态。
 */
public enum ApprovalStatus {
    /** 当前线程没有审批上下文。 */
    NONE,
    /** 已创建审批任务，等待人工处理。 */
    WAITING,
    /** 审批已通过，可继续执行。 */
    APPROVED,
    /** 审批被拒绝，通常对应 run 失败或终止。 */
    REJECTED,
    /** 需要用户进一步补充说明。 */
    NEEDS_CLARIFICATION
}
