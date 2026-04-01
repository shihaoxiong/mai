package com.mai.deerflow.backend.runtime.memory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 为长期记忆更新提供按线程维度的 debounce 队列。
 *
 * 设计目标：
 * 1. 短时间内同一线程的多次记忆更新只保留最新上下文
 * 2. 不阻塞主 run 完成
 * 3. 仍然把每次 enqueue 的结果通过 future 回传给调用方，便于测试和日志观察
 */
public class MemoryUpdateQueue {

    private final StructuredMemoryUpdater updater;
    private final Executor executor;
    private final ScheduledExecutorService scheduler;
    private final Duration debounceDuration;

    private final Object monitor = new Object();
    private final Map<String, PendingUpdate> pendingUpdates = new LinkedHashMap<>();
    private ScheduledFuture<?> scheduledFlush;

    public MemoryUpdateQueue(StructuredMemoryUpdater updater,
                             Executor executor,
                             ScheduledExecutorService scheduler,
                             Duration debounceDuration) {
        this.updater = updater;
        this.executor = executor;
        this.scheduler = scheduler;
        this.debounceDuration = debounceDuration == null ? Duration.ZERO : debounceDuration;
    }

    public CompletableFuture<List<MemoryFact>> enqueue(MemoryExtractionRequest request) {
        CompletableFuture<List<MemoryFact>> future = new CompletableFuture<>();
        synchronized (monitor) {
            String key = keyOf(request);
            PendingUpdate pendingUpdate = pendingUpdates.computeIfAbsent(key, ignored -> new PendingUpdate(request));
            pendingUpdate.request = request;
            pendingUpdate.listeners.add(future);
            scheduleFlushLocked();
        }
        return future;
    }

    public void flush() {
        Map<String, PendingUpdate> snapshot = snapshotPendingUpdates();
        processSnapshot(snapshot);
    }

    private void scheduleFlushLocked() {
        if (scheduledFlush != null) {
            scheduledFlush.cancel(false);
            scheduledFlush = null;
        }

        long delayMillis = Math.max(0L, debounceDuration.toMillis());
        if (delayMillis == 0L) {
            flush();
            return;
        }

        scheduledFlush = scheduler.schedule(this::flush, delayMillis, TimeUnit.MILLISECONDS);
    }

    private Map<String, PendingUpdate> snapshotPendingUpdates() {
        synchronized (monitor) {
            if (scheduledFlush != null) {
                scheduledFlush.cancel(false);
                scheduledFlush = null;
            }
            if (pendingUpdates.isEmpty()) {
                return Map.of();
            }
            Map<String, PendingUpdate> snapshot = new LinkedHashMap<>(pendingUpdates);
            pendingUpdates.clear();
            return snapshot;
        }
    }

    private void processSnapshot(Map<String, PendingUpdate> snapshot) {
        snapshot.values().forEach(pendingUpdate -> CompletableFuture
                .supplyAsync(() -> updater.update(pendingUpdate.request), executor)
                .whenComplete((facts, exception) -> completeListeners(pendingUpdate.listeners, facts, exception)));
    }

    private void completeListeners(List<CompletableFuture<List<MemoryFact>>> listeners,
                                   List<MemoryFact> facts,
                                   Throwable exception) {
        for (CompletableFuture<List<MemoryFact>> listener : listeners) {
            if (exception == null) {
                listener.complete(facts == null ? List.of() : facts);
            }
            else {
                listener.completeExceptionally(exception);
            }
        }
    }

    private String keyOf(MemoryExtractionRequest request) {
        String userId = request.userId() == null ? "anonymous" : request.userId().trim();
        String threadId = request.threadId() == null ? "unknown-thread" : request.threadId().trim();
        return userId + "::" + threadId;
    }

    private static final class PendingUpdate {

        private MemoryExtractionRequest request;
        private final List<CompletableFuture<List<MemoryFact>>> listeners = new ArrayList<>();

        private PendingUpdate(MemoryExtractionRequest request) {
            this.request = request;
        }
    }
}
