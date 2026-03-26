package com.mai.deerflow.backend.runtime.model;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/models")
public class ModelRegistryController {

    private final ModelRegistryService modelRegistryService;

    public ModelRegistryController(ModelRegistryService modelRegistryService) {
        this.modelRegistryService = modelRegistryService;
    }

    @GetMapping
    public List<ModelDescriptor> listModels() {
        return modelRegistryService.listModels();
    }
}
