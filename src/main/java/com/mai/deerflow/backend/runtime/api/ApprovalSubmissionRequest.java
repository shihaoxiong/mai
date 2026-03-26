package com.mai.deerflow.backend.runtime.api;

/**
 * 审批提交请求体。
 */
public record ApprovalSubmissionRequest(
        ApprovalDecision decision,
        String comment
) {
}
