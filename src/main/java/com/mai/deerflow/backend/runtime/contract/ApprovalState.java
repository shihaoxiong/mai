package com.mai.deerflow.backend.runtime.contract;

/**
 * 线程当前绑定的审批状态快照。
 */
public record ApprovalState(
        String approvalId,
        ApprovalStatus status,
        String reason
) {
}
