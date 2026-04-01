package com.mai.deerflow.backend.runtime.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryExtractorJobTests {

    @TempDir
    Path tempDir;

    private FileMemoryStore memoryStore;
    private MemoryExtractorJob memoryExtractorJob;

    @BeforeEach
    void setUp() {
        MemoryStoreProperties properties = new MemoryStoreProperties();
        properties.setBaseDir(tempDir);
        memoryStore = new FileMemoryStore(properties, new ObjectMapper());
        memoryExtractorJob = new MemoryExtractorJob(memoryStore, Runnable::run);
    }

    @Test
    void shouldExtractPreferenceFactAndContextMemories() {
        List<MemoryFact> extractedFacts = memoryExtractorJob.schedule(new MemoryExtractionRequest(
                "memory-user",
                "thread-1",
                "run-1",
                "I use Java 17 locally. Please keep comments in Chinese and prefer small refactors.",
                "Processed: memory extraction demo",
                "memory extraction demo"
        )).join();

        assertThat(extractedFacts)
                .extracting(MemoryFact::category)
                .containsExactly("preference", "fact", "context");
        assertThat(extractedFacts)
                .extracting(MemoryFact::content)
                .contains("Please keep comments in Chinese and prefer small refactors", "I use Java 17 locally");
        assertThat(extractedFacts.get(2).content()).startsWith("当前任务上下文：");
        assertThat(memoryStore.list("memory-user")).hasSize(3);
        assertThat(memoryStore.loadProfile("memory-user").user().topOfMind().summary())
                .contains("memory extraction demo");
        assertThat(memoryStore.loadProfile("memory-user").history().longTermBackground().summary())
                .contains("I use Java 17 locally");
    }

    @Test
    void shouldSkipDuplicateMemoriesAcrossRuns() {
        MemoryExtractionRequest request = new MemoryExtractionRequest(
                "memory-user-2",
                "thread-2",
                "run-2",
                "I use Java 17 locally. Please keep comments in Chinese.",
                "Processed: duplicate run",
                "duplicate run"
        );

        List<MemoryFact> firstBatch = memoryExtractorJob.schedule(request).join();
        List<MemoryFact> secondBatch = memoryExtractorJob.schedule(request).join();

        assertThat(firstBatch).hasSize(3);
        assertThat(secondBatch).isEmpty();
        assertThat(memoryStore.list("memory-user-2")).hasSize(3);
    }

    @Test
    void shouldIgnoreRequestsWithoutUserId() {
        List<MemoryFact> extractedFacts = memoryExtractorJob.schedule(new MemoryExtractionRequest(
                null,
                "thread-3",
                "run-3",
                "I use Java 17 locally",
                "Processed: ignored",
                "ignored"
        )).join();

        assertThat(extractedFacts).isEmpty();
        assertThat(memoryStore.list("memory-user-3")).isEmpty();
    }
}
