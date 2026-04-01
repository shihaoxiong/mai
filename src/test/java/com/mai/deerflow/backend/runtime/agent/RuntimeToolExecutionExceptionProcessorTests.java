package com.mai.deerflow.backend.runtime.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeToolExecutionExceptionProcessorTests {

    private final RuntimeToolExecutionExceptionProcessor processor = new RuntimeToolExecutionExceptionProcessor();

    @Test
    void shouldFormatToolExecutionExceptionForModel() {
        ToolExecutionException exception = new ToolExecutionException(
                DefaultToolDefinition.builder()
                        .name("broken_tool")
                        .description("broken")
                        .inputSchema("{\"type\":\"object\"}")
                        .build(),
                new IllegalStateException("broken for alpha")
        );

        String message = processor.process(exception);

        assertThat(message)
                .contains("broken_tool")
                .contains("IllegalStateException")
                .contains("broken for alpha")
                .contains("Continue with available context");
    }
}
