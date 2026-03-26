package com.mai.deerflow.backend.runtime.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mai.deerflow.backend.runtime.config.RuntimeConfigRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ModelRegistryService {

    static final String MODELS_CONFIG_KEY = "models.registry";

    private static final TypeReference<List<ModelDescriptor>> MODEL_LIST_TYPE = new TypeReference<>() {
    };

    private final RuntimeConfigRepository runtimeConfigRepository;

    public ModelRegistryService(RuntimeConfigRepository runtimeConfigRepository) {
        this.runtimeConfigRepository = runtimeConfigRepository;
    }

    public List<ModelDescriptor> listModels() {
        return runtimeConfigRepository.find(MODELS_CONFIG_KEY, MODEL_LIST_TYPE)
                .orElseGet(this::defaultModels);
    }

    public List<ModelDescriptor> replaceModels(List<ModelDescriptor> models) {
        return runtimeConfigRepository.save(MODELS_CONFIG_KEY, List.copyOf(models));
    }

    private List<ModelDescriptor> defaultModels() {
        return List.of(
                new ModelDescriptor(
                        "fallback-chat",
                        "internal",
                        true,
                        List.of("chat", "structured-output"),
                        "Fallback local chat model used when no provider-specific model is configured."
                )
        );
    }
}
