package com.mai.deerflow.backend.p0;

import com.mai.deerflow.backend.runtime.contract.RunEventEnvelope;
import com.mai.deerflow.backend.runtime.contract.RunEventType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Service
public class P0SseDemoService {

    public Flux<ServerSentEvent<RunEventEnvelope<String>>> stream(String threadId) {
        String runId = threadId + "-demo-run";
        List<RunEventEnvelope<String>> events = List.of(
                new RunEventEnvelope<>(threadId, runId, RunEventType.RUN_STARTED, "demo stream bootstrapped"),
                new RunEventEnvelope<>(threadId, runId, RunEventType.TOKEN_DELTA, "hello from sse"),
                new RunEventEnvelope<>(threadId, runId, RunEventType.RUN_COMPLETED, "demo stream finished")
        );

        return Flux.fromIterable(events)
                .map(event -> ServerSentEvent.<RunEventEnvelope<String>>builder()
                        .id(event.runId() + ":" + event.eventType().wireName())
                        .event(event.eventType().wireName())
                        .data(event)
                        .build());
    }
}
