package com.mai.deerflow.backend.runtime.api;

/**
 * 发起线程运行请求体。
 */
public record ThreadRunRequest(
        String message,
        Boolean approvalRequired,
        String approvalReason
) {
}
