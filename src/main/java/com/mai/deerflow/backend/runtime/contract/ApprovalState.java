package com.mai.deerflow.backend.runtime.contract;

public record ApprovalState(
        String approvalId,
        ApprovalStatus status,
        String reason
) {
}
