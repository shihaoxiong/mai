package com.mai.deerflow.backend.runtime.api;

import com.mai.deerflow.backend.runtime.contract.ApprovalStatus;

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
