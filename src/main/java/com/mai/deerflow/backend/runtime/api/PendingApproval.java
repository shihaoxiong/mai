package com.mai.deerflow.backend.runtime.api;

import com.mai.deerflow.backend.runtime.contract.ApprovalStatus;

/**
 * 线程恢复所需的待审批上下文。
 */
public record PendingApproval(
        String threadId,
        String runId,
        String approvalId,
        String message,
        String reason,
        ApprovalStatus status,
        String comment
) {
}
