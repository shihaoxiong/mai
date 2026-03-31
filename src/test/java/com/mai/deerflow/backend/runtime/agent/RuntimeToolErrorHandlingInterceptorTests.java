package com.mai.deerflow.backend.runtime.agent;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeToolErrorHandlingInterceptorTests {

    private final RuntimeToolErrorHandlingInterceptor interceptor = new RuntimeToolErrorHandlingInterceptor();

    @Test
    void shouldConvertToolExceptionToErrorResponse() {
        ToolCallRequest request = ToolCallRequest.builder()
                .toolName("broken_tool")
                .toolCallId("call-1")
                .arguments("{\"input\":\"alpha\"}")
                .context(Map.of())
                .build();

        ToolCallResponse response = interceptor.interceptToolCall(request, ignored -> {
            throw new IllegalStateException("broken for alpha");
        });

        assertThat(response.getToolName()).isEqualTo("broken_tool");
        assertThat(response.getToolCallId()).isEqualTo("call-1");
        assertThat(response.getStatus()).isNull();
        assertThat(response.getResult()).contains("broken_tool").contains("broken for alpha");
        assertThat(response.getMetadata()).containsEntry("exceptionType", IllegalStateException.class.getName());
    }

    @Test
    void shouldRethrowClarificationException() {
        ToolCallRequest request = ToolCallRequest.builder()
                .toolName("ask_clarification")
                .toolCallId("clarify-1")
                .arguments("{}")
                .context(Map.of())
                .build();

        assertThatThrownBy(() -> interceptor.interceptToolCall(request, ignored -> {
            throw new AgentClarificationRequestedException(new AskClarificationRequest("question", null, null, null));
        }))
                .isInstanceOf(AgentClarificationRequestedException.class);
    }
}
