package com.mai.deerflow.backend.runtime.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryUpdateQueueTests {

    @TempDir
    Path tempDir;

    @Test
    void shouldDebounceRepeatedUpdatesForSameThreadAndKeepLatestRequest() {
        MemoryStoreProperties properties = new MemoryStoreProperties();
        properties.setBaseDir(tempDir);
        FileMemoryStore memoryStore = new FileMemoryStore(properties, new ObjectMapper());
        StructuredMemoryUpdater updater = new StructuredMemoryUpdater(memoryStore, memoryStore);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "memory-update-queue-test");
            thread.setDaemon(true);
            return thread;
        });
        MemoryUpdateQueue queue = new MemoryUpdateQueue(
                updater,
                Runnable::run,
                scheduler,
                Duration.ofMillis(30)
        );

        try {
            CompletableFuture<List<MemoryFact>> first = queue.enqueue(new MemoryExtractionRequest(
                    "debounce-user",
                    "thread-1",
                    "run-1",
                    "I use Java 17 locally.",
                    "Processed first",
                    "first"
            ));
            CompletableFuture<List<MemoryFact>> second = queue.enqueue(new MemoryExtractionRequest(
                    "debounce-user",
                    "thread-1",
                    "run-2",
                    "I use Java 21 locally. Please keep comments in Chinese.",
                    "Processed second",
                    "second"
            ));

            List<MemoryFact> secondResult = second.join();
            List<MemoryFact> firstResult = first.join();

            assertThat(firstResult).isEqualTo(secondResult);
            assertThat(memoryStore.list("debounce-user"))
                    .extracting(MemoryFact::content)
                    .contains("I use Java 21 locally", "Please keep comments in Chinese")
                    .doesNotContain("I use Java 17 locally");
        }
        finally {
            scheduler.shutdownNow();
        }
    }
}
