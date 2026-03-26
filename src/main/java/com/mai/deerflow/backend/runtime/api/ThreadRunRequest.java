package com.mai.deerflow.backend.runtime.api;

public record ThreadRunRequest(
        String message,
        Boolean approvalRequired,
        String approvalReason
) {
}
