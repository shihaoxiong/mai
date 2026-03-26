package com.mai.deerflow.backend.p0;

public record RunEvent(
        String threadId,
        String runId,
        String eventType,
        String payload
) {
}
