package com.mai.deerflow.backend.runtime.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.time.Duration;

@Service
/**
 * 运行后异步抽取长期记忆的后台任务。
 *
 * 当前实现已升级为“debounce 队列 + 结构化 profile 更新”：
 * 1. 同一线程在短时间内的多次更新会被折叠
 * 2. 更新结果同时写入 facts 与结构化记忆档案
 * 3. 主 run 只负责调度，不等待结构化记忆真正完成
 */
public class MemoryExtractorJob {

    private static final Logger logger = LoggerFactory.getLogger(MemoryExtractorJob.class);

    private final MemoryUpdateQueue memoryUpdateQueue;

    @Autowired
    public MemoryExtractorJob(MemoryStore memoryStore,
                              MemoryUpdateQueueProperties properties) {
        this(
                memoryStore,
                resolveProfileStore(memoryStore),
                ForkJoinPool.commonPool(),
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "memory-update-queue");
                    thread.setDaemon(true);
                    return thread;
                }),
                Duration.ofMillis(properties == null ? 300L : Math.max(0L, properties.getDebounceMillis()))
        );
    }

    /**
     * 允许在测试或特定部署场景下替换异步执行器。
     */
    public MemoryExtractorJob(MemoryStore memoryStore, Executor executor) {
        this(
                memoryStore,
                resolveProfileStore(memoryStore),
                executor,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "memory-update-queue-test");
                    thread.setDaemon(true);
                    return thread;
                }),
                Duration.ZERO
        );
    }

    /**
     * 异步调度一次记忆抽取；失败会被吞掉并记录日志，不影响主运行结果。
     */
    public CompletableFuture<List<MemoryFact>> schedule(MemoryExtractionRequest request) {
        if (request == null || !hasText(request.userId()) || !hasText(request.userMessage())) {
            return CompletableFuture.completedFuture(List.of());
        }

        return memoryUpdateQueue.enqueue(request)
                .exceptionally(exception -> {
                    logger.warn("Failed to extract memories for user {}", request.userId(), exception);
                    return List.of();
                });
    }

    void flush() {
        memoryUpdateQueue.flush();
    }

    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private MemoryExtractorJob(MemoryStore memoryStore,
                               MemoryProfileStore memoryProfileStore,
                               Executor executor,
                               ScheduledExecutorService scheduler,
                               Duration debounceDuration) {
        StructuredMemoryUpdater updater = new StructuredMemoryUpdater(memoryStore, memoryProfileStore);
        this.memoryUpdateQueue = new MemoryUpdateQueue(updater, executor, scheduler, debounceDuration);
    }

    private static MemoryProfileStore resolveProfileStore(MemoryStore memoryStore) {
        if (memoryStore instanceof MemoryProfileStore memoryProfileStore) {
            return memoryProfileStore;
        }
        return new InMemoryProfileStore();
    }

    private static final class InMemoryProfileStore implements MemoryProfileStore {

        @Override
        public StructuredMemoryProfile loadProfile(String userId) {
            return StructuredMemoryProfile.empty();
        }

        @Override
        public StructuredMemoryProfile saveProfile(String userId, StructuredMemoryProfile profile) {
            return profile == null ? StructuredMemoryProfile.empty() : profile;
        }
    }
}
