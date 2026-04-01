package com.mai.deerflow.backend.runtime.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.springframework.stereotype.Component;

@Component
/**
 * 统一负责把 `LeadAgentDefinition` 装配成 `ReactAgent`。
 */
public class LeadAgentFactory {

    /**
     * 根据定义对象创建运行时可执行的 lead agent。
     */
    public ReactAgent create(LeadAgentDefinition definition) {
        var builder = ReactAgent.builder()
                .name(definition.name())
                .description(definition.description())
                .instruction(definition.instruction())
                .model(definition.model())
                .releaseThread(definition.releaseThread());

        if (definition.chatOptions() != null) {
            builder.chatOptions(definition.chatOptions());
        }
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
        if (definition.toolExecutionExceptionProcessor() != null) {
            builder.toolExecutionExceptionProcessor(definition.toolExecutionExceptionProcessor());
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
