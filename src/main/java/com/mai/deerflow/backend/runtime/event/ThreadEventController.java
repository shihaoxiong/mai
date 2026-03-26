package com.mai.deerflow.backend.runtime.event;

import com.mai.deerflow.backend.runtime.contract.RunEventEnvelope;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/threads/{threadId}/events")
public class ThreadEventController {

    private final ThreadEventService threadEventService;

    public ThreadEventController(ThreadEventService threadEventService) {
        this.threadEventService = threadEventService;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<RunEventEnvelope<Object>>> stream(@PathVariable String threadId) {
        return threadEventService.stream(threadId);
    }
}
