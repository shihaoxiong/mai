package com.mai.deerflow.backend.runtime.api;

/**
 * 恢复线程执行时的请求体。
 */
public record ResumeThreadRequest(String comment) {
}
