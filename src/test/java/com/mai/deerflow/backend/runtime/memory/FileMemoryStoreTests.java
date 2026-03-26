package com.mai.deerflow.backend.runtime.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FileMemoryStoreTests {

    @TempDir
    Path tempDir;

    private MemoryStoreProperties properties;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        properties = new MemoryStoreProperties();
        properties.setBaseDir(tempDir);
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldSaveAndListFactsByUser() {
        FileMemoryStore memoryStore = new FileMemoryStore(properties, objectMapper);

        MemoryFact preferenceFact = memoryStore.save("user-1", new MemoryFact(
                null,
                "preference",
                "Prefers concise answers",
                0.95d,
                "thread-a",
                null,
                null,
                Map.of("source", "manual")
        ));
        MemoryFact projectFact = memoryStore.save("user-1", new MemoryFact(
                null,
                "context",
                "Working on the Java DeerFlow backend",
                0.72d,
                "thread-b",
                null,
                null,
                Map.of()
        ));

        List<MemoryFact> facts = memoryStore.list("user-1");

        assertThat(facts)
                .extracting(MemoryFact::content)
                .containsExactly("Prefers concise answers", "Working on the Java DeerFlow backend");
        assertThat(preferenceFact.memoryId()).isNotBlank();
        assertThat(preferenceFact.createdAt()).isNotBlank();
        assertThat(preferenceFact.updatedAt()).isNotBlank();
        assertThat(projectFact.memoryId()).isNotBlank();
    }

    @Test
    void shouldFilterFactsByConfidenceAndLimit() {
        FileMemoryStore memoryStore = new FileMemoryStore(properties, objectMapper);

        memoryStore.saveAll("user-2", List.of(
                new MemoryFact(null, "fact", "Uses Java 17 locally", 0.88d, "thread-a", null, null, Map.of()),
                new MemoryFact(null, "fact", "Interested in memory extraction", 0.67d, "thread-b", null, null, Map.of()),
                new MemoryFact(null, "fact", "Wants automated commits after tests", 0.93d, "thread-c", null, null, Map.of())
        ));

        List<MemoryFact> facts = memoryStore.list("user-2", new MemoryQuery(2, 0.80d));

        assertThat(facts)
                .extracting(MemoryFact::content)
                .containsExactly("Wants automated commits after tests", "Uses Java 17 locally");
    }

    @Test
    void shouldPersistFactsAcrossRepositoryInstancesAndIsolateUsers() {
        FileMemoryStore firstMemoryStore = new FileMemoryStore(properties, objectMapper);
        firstMemoryStore.save("alice@example.com", new MemoryFact(
                null,
                "profile",
                "Alice maintains the backend",
                0.91d,
                "thread-1",
                null,
                null,
                Map.of()
        ));
        firstMemoryStore.save("bob@example.com", new MemoryFact(
                null,
                "profile",
                "Bob focuses on frontend integration",
                0.85d,
                "thread-2",
                null,
                null,
                Map.of()
        ));

        FileMemoryStore secondMemoryStore = new FileMemoryStore(properties, objectMapper);

        assertThat(secondMemoryStore.list("alice@example.com"))
                .extracting(MemoryFact::content)
                .containsExactly("Alice maintains the backend");
        assertThat(secondMemoryStore.list("bob@example.com"))
                .extracting(MemoryFact::content)
                .containsExactly("Bob focuses on frontend integration");
    }

    @Test
    void shouldDeleteFactAndRemoveEmptyUserFile() throws Exception {
        FileMemoryStore memoryStore = new FileMemoryStore(properties, objectMapper);

        MemoryFact savedFact = memoryStore.save("user-3", new MemoryFact(
                null,
                "fact",
                "Temporary memory",
                0.50d,
                "thread-delete",
                null,
                null,
                Map.of()
        ));

        assertThat(memoryStore.delete("user-3", savedFact.memoryId())).isTrue();
        assertThat(memoryStore.list("user-3")).isEmpty();
        try (Stream<Path> files = Files.list(tempDir)) {
            assertThat(files).isEmpty();
        }
    }
}
