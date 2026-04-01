package com.mai.deerflow.backend.runtime.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileRuntimeConfigRepositoryTests {

    @TempDir
    Path tempDir;

    private RuntimeConfigProperties properties;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        properties = new RuntimeConfigProperties();
        properties.setFile(tempDir.resolve("runtime-config.json"));
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldSaveAndLoadTypedConfig() {
        FileRuntimeConfigRepository repository = new FileRuntimeConfigRepository(properties, objectMapper);

        ModelConfig modelConfig = new ModelConfig("deepseek-chat", true, List.of("thinking", "structured-output"));
        repository.save("models.default", modelConfig);

        assertThat(repository.find("models.default", ModelConfig.class))
                .contains(modelConfig);
    }

    @Test
    void shouldReloadConfigAcrossRepositoryInstances() {
        FileRuntimeConfigRepository firstRepository = new FileRuntimeConfigRepository(properties, objectMapper);
        firstRepository.save("mcp.servers", Map.of("filesystem", Map.of("enabled", true)));

        FileRuntimeConfigRepository secondRepository = new FileRuntimeConfigRepository(properties, objectMapper);

        assertThat(secondRepository.find("mcp.servers", new TypeReference<Map<String, Map<String, Boolean>>>() {
        })).contains(Map.of("filesystem", Map.of("enabled", true)));
    }

    @Test
    void shouldDeleteStoredConfig() {
        FileRuntimeConfigRepository repository = new FileRuntimeConfigRepository(properties, objectMapper);
        repository.save("skills.enabled", List.of("analysis", "search"));

        assertThat(repository.delete("skills.enabled")).isTrue();
        assertThat(repository.find("skills.enabled", new TypeReference<List<String>>() {
        })).isEmpty();
        assertThat(repository.list()).doesNotContainKey("skills.enabled");
    }

    record ModelConfig(String name, boolean enabled, List<String> capabilities) {
    }
}
