package com.mai.deerflow.backend.runtime.api;

import com.mai.deerflow.backend.runtime.contract.ThreadStateSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/threads")
public class ThreadRuntimeController {

    private final ThreadRuntimeService threadRuntimeService;

    public ThreadRuntimeController(ThreadRuntimeService threadRuntimeService) {
        this.threadRuntimeService = threadRuntimeService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ThreadStateSnapshot> createThread(@RequestBody(required = false) ThreadCreateRequest request) {
        String threadId = request == null ? null : request.threadId();
        return Mono.fromCallable(() -> threadRuntimeService.createThread(threadId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{threadId}")
    public Mono<ThreadStateSnapshot> getThread(@PathVariable String threadId) {
        return Mono.fromCallable(() -> threadRuntimeService.getThread(threadId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{threadId}/runs")
    public Mono<ThreadStateSnapshot> runThread(@PathVariable String threadId, @RequestBody ThreadRunRequest request) {
        return Mono.fromCallable(() -> threadRuntimeService.runThread(threadId, request.message()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{threadId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteThread(@PathVariable String threadId) {
        return Mono.fromRunnable(() -> threadRuntimeService.deleteThread(threadId))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
