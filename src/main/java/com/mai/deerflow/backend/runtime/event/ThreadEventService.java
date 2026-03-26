package com.mai.deerflow.backend.runtime.event;

import com.mai.deerflow.backend.runtime.contract.RunEventEnvelope;
import com.mai.deerflow.backend.runtime.contract.RunEventType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ThreadEventService {

    private final Map<String, Sinks.Many<RunEventEnvelope<Object>>> sinks = new ConcurrentHashMap<>();

    public void emit(String threadId, String runId, RunEventType eventType, Object payload) {
        sink(threadId).tryEmitNext(new RunEventEnvelope<>(threadId, runId, eventType, payload));
    }

    public Flux<ServerSentEvent<RunEventEnvelope<Object>>> stream(String threadId) {
        return sink(threadId).asFlux()
                .map(event -> ServerSentEvent.<RunEventEnvelope<Object>>builder()
                        .id(event.runId() + ":" + event.eventType().wireName())
                        .event(event.eventType().wireName())
                        .data(event)
                        .build());
    }

    private Sinks.Many<RunEventEnvelope<Object>> sink(String threadId) {
        return sinks.computeIfAbsent(threadId, ignored -> Sinks.many().replay().limit(32));
    }
}
