package com.mai.deerflow.backend.p0;

import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Service
public class P0SseDemoService {

    public Flux<ServerSentEvent<RunEvent>> stream(String threadId) {
        String runId = threadId + "-demo-run";
        List<RunEvent> events = List.of(
                new RunEvent(threadId, runId, "run.started", "demo stream bootstrapped"),
                new RunEvent(threadId, runId, "token.delta", "hello from sse"),
                new RunEvent(threadId, runId, "run.completed", "demo stream finished")
        );

        return Flux.fromIterable(events)
                .map(event -> ServerSentEvent.<RunEvent>builder()
                        .id(event.runId() + ":" + event.eventType())
                        .event(event.eventType())
                        .data(event)
                        .build());
    }
}
