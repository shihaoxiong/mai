package com.mai.deerflow.backend.runtime.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.springframework.stereotype.Component;

@Component
public class LeadAgentFactory {

    public ReactAgent create(LeadAgentDefinition definition) {
        var builder = ReactAgent.builder()
                .name(definition.name())
                .description(definition.description())
                .instruction(definition.instruction())
                .model(definition.model())
                .releaseThread(definition.releaseThread());

        if (!definition.systemPrompt().isBlank()) {
            builder.systemPrompt(definition.systemPrompt());
        }
        if (!definition.tools().isEmpty()) {
            builder.tools(definition.tools());
        }
        if (!definition.hooks().isEmpty()) {
            builder.hooks(definition.hooks());
        }
        if (!definition.interceptors().isEmpty()) {
            builder.interceptors(definition.interceptors());
        }
        if (definition.saver() != null) {
            builder.saver(definition.saver());
        }
        if (definition.compileConfig() != null) {
            builder.compileConfig(definition.compileConfig());
        }

        return builder.build();
    }
}
