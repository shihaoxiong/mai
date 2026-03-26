package com.mai.deerflow.backend.runtime.api;

public record ApprovalSubmissionRequest(
        ApprovalDecision decision,
        String comment
) {
}
