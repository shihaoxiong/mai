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
/**
 * 线程事件流服务。
 *
 * 当前基于 replay sink 提供短历史回放，便于前端重新订阅时看到最近关键事件。
 */
public class ThreadEventService {

    private final Map<String, Sinks.Many<RunEventEnvelope<Object>>> sinks = new ConcurrentHashMap<>();

    /**
     * 向线程事件流发送一个事件。
     */
    public void emit(String threadId, String runId, RunEventType eventType, Object payload) {
        sink(threadId).tryEmitNext(new RunEventEnvelope<>(threadId, runId, eventType, payload));
    }

    /**
     * 订阅指定线程的事件流。
     */
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
