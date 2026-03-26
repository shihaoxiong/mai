package com.mai.deerflow.backend.runtime.api;

import com.mai.deerflow.backend.runtime.contract.ApprovalStatus;

/**
 * 持久化到线程 metadata 目录中的待审批上下文。
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
