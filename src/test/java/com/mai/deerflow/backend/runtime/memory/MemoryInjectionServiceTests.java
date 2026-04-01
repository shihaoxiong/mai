package com.mai.deerflow.backend.runtime.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryInjectionServiceTests {

    @TempDir
    Path tempDir;

    private FileMemoryStore memoryStore;
    private MemoryInjectionProperties properties;

    @BeforeEach
    void setUp() {
        MemoryStoreProperties memoryStoreProperties = new MemoryStoreProperties();
        memoryStoreProperties.setBaseDir(tempDir);
        memoryStore = new FileMemoryStore(memoryStoreProperties, new ObjectMapper());
        properties = new MemoryInjectionProperties();
    }

    @Test
    void shouldInjectFilteredMemoriesIntoAgentInput() {
        memoryStore.save("injection-user", new MemoryFact(
                null,
                "preference",
                "Keep comments in Chinese",
                0.92d,
                "thread-a",
                null,
                null,
                Map.of()
        ));
        memoryStore.save("injection-user", new MemoryFact(
                null,
                "fact",
                "Uses Java 17 locally",
                0.80d,
                "thread-b",
                null,
                null,
                Map.of()
        ));
        memoryStore.save("injection-user", new MemoryFact(
                null,
                "context",
                "Temporary low-confidence note",
                0.42d,
                "thread-c",
                null,
                null,
                Map.of()
        ));
        memoryStore.saveProfile(
                "injection-user",
                new StructuredMemoryProfile(
                        "1.0",
                        "2026-03-30T00:00:00Z",
                        new MemoryUserProfile(
                                new MemoryProfileSection("Maintains the Java DeerFlow backend", "2026-03-30T00:00:00Z"),
                                MemoryProfileSection.empty(),
                                new MemoryProfileSection("Currently focused on runtime reliability", "2026-03-30T00:00:00Z")
                        ),
                        new MemoryHistoryProfile(
                                new MemoryProfileSection("Recently worked on SSE and MCP transport", "2026-03-30T00:00:00Z"),
                                MemoryProfileSection.empty(),
                                new MemoryProfileSection("Prefers incremental rollout with tests", "2026-03-30T00:00:00Z")
                        )
                )
        );

        MemoryInjectionService memoryInjectionService = new MemoryInjectionService(memoryStore, properties);

        MemoryInjectionResult injectionResult = memoryInjectionService.inject(
                "injection-user",
                "Please help me continue the backend task."
        );

        assertThat(injectionResult.memoryFacts()).hasSize(2);
        assertThat(injectionResult.effectiveUserInput())
                .contains("用户画像")
                .contains("历史摘要")
                .contains("Maintains the Java DeerFlow backend")
                .contains("Keep comments in Chinese")
                .contains("Uses Java 17 locally")
                .contains("Please help me continue the backend task.")
                .doesNotContain("Temporary low-confidence note");
    }

    @Test
    void shouldRespectDisabledInjectionSetting() {
        properties.setEnabled(false);
        memoryStore.save("disabled-user", new MemoryFact(
                null,
                "preference",
                "Prefers concise answers",
                0.95d,
                "thread-disabled",
                null,
                null,
                Map.of()
        ));

        MemoryInjectionService memoryInjectionService = new MemoryInjectionService(memoryStore, properties);

        MemoryInjectionResult injectionResult = memoryInjectionService.inject(
                "disabled-user",
                "Continue the task."
        );

        assertThat(injectionResult.memoryFacts()).isEmpty();
        assertThat(injectionResult.effectiveUserInput()).isEqualTo("Continue the task.");
    }
}
