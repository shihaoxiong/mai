package com.mai.deerflow.backend.p0;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/p0")
public class P0SseDemoController {

    private final P0SseDemoService p0SseDemoService;

    public P0SseDemoController(P0SseDemoService p0SseDemoService) {
        this.p0SseDemoService = p0SseDemoService;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<RunEvent>> stream(@RequestParam String threadId) {
        return p0SseDemoService.stream(threadId);
    }
}
