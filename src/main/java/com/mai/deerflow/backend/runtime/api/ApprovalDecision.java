package com.mai.deerflow.backend.runtime.api;

/**
 * 审批提交时允许的决策结果。
 */
public enum ApprovalDecision {
    /** 审批通过，线程可以继续恢复执行。 */
    APPROVE,
    /** 审批拒绝，线程应终止或转失败。 */
    REJECT
}
