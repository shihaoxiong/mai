package com.mai.deerflow.backend.runtime.contract;

public record RunEventEnvelope<T>(
        String threadId,
        String runId,
        RunEventType eventType,
        T payload
) {
}
