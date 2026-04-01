package com.mai.deerflow.backend.runtime.memory;

/**
 * 运行完成后提交给长期记忆抽取任务的最小上下文。
 */
public record MemoryExtractionRequest(
        String userId,
        String threadId,
        String runId,
        String userMessage,
        String assistantOutput,
        String title
) {
}
