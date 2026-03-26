package com.mai.deerflow.backend.runtime.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mai.deerflow.backend.runtime.config.FileRuntimeConfigRepository;
import com.mai.deerflow.backend.runtime.config.RuntimeConfigProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRegistryServiceTests {

    @TempDir
    Path tempDir;

    private ModelRegistryService modelRegistryService;

    @BeforeEach
    void setUp() {
        RuntimeConfigProperties properties = new RuntimeConfigProperties();
        properties.setFile(tempDir.resolve("runtime-config.json"));
        FileRuntimeConfigRepository repository = new FileRuntimeConfigRepository(properties, new ObjectMapper());
        modelRegistryService = new ModelRegistryService(repository);
    }

    @Test
    void shouldReturnDefaultModelsWhenConfigIsMissing() {
        assertThat(modelRegistryService.listModels())
                .hasSize(1)
                .first()
                .extracting(ModelDescriptor::id)
                .isEqualTo("fallback-chat");
    }

    @Test
    void shouldReturnConfiguredModelsWhenPresent() {
        List<ModelDescriptor> configuredModels = List.of(
                new ModelDescriptor("deepseek-chat", "deepseek", true, List.of("chat", "thinking"), "DeepSeek chat model"),
                new ModelDescriptor("qwen-max", "qwen", false, List.of("chat", "vision"), "Disabled Qwen model")
        );

        modelRegistryService.replaceModels(configuredModels);

        assertThat(modelRegistryService.listModels()).containsExactlyElementsOf(configuredModels);
    }
}
