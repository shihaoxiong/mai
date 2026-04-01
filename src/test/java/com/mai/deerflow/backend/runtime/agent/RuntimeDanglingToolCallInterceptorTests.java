package com.mai.deerflow.backend.runtime.agent;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeDanglingToolCallInterceptorTests {

    private final RuntimeDanglingToolCallInterceptor interceptor = new RuntimeDanglingToolCallInterceptor();

    @Test
    void shouldPatchDanglingToolCallsBeforeModelInvocation() {
        AssistantMessage danglingToolCall = AssistantMessage.builder()
                .content("Call read_file first")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "tool-call-1",
                        "function",
                        "read_file",
                        "{\"path\":\"/workspace/brief.md\"}"
                )))
                .build();

        ModelRequest request = ModelRequest.builder()
                .systemMessage(new SystemMessage("system"))
                .messages(List.of(
                        new UserMessage("analyze the brief"),
                        danglingToolCall,
                        new UserMessage("continue")
                ))
                .build();

        AtomicReference<ModelRequest> capturedRequest = new AtomicReference<>();
        interceptor.interceptModel(request, modelRequest -> {
            capturedRequest.set(modelRequest);
            return ModelResponse.of(new AssistantMessage("ok"));
        });

        List<Message> patchedMessages = capturedRequest.get().getMessages();
        assertThat(patchedMessages).hasSize(4);
        assertThat(patchedMessages.get(2)).isInstanceOf(ToolResponseMessage.class);

        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) patchedMessages.get(2);
        assertThat(toolResponseMessage.getResponses()).hasSize(1);
        assertThat(toolResponseMessage.getResponses().get(0).id()).isEqualTo("tool-call-1");
        assertThat(toolResponseMessage.getResponses().get(0).name()).isEqualTo("read_file");
        assertThat(toolResponseMessage.getResponses().get(0).responseData())
                .contains("interrupted")
                .contains("did not return");
    }

    @Test
    void shouldNotPatchWhenToolResponseAlreadyExists() {
        AssistantMessage completedToolCall = AssistantMessage.builder()
                .content("Call read_file first")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "tool-call-1",
                        "function",
                        "read_file",
                        "{\"path\":\"/workspace/brief.md\"}"
                )))
                .build();
        ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "tool-call-1",
                        "read_file",
                        "brief content"
                )))
                .build();

        ModelRequest request = ModelRequest.builder()
                .systemMessage(new SystemMessage("system"))
                .messages(List.of(
                        new UserMessage("analyze the brief"),
                        completedToolCall,
                        toolResponseMessage,
                        new UserMessage("continue")
                ))
                .build();

        AtomicReference<ModelRequest> capturedRequest = new AtomicReference<>();
        interceptor.interceptModel(request, modelRequest -> {
            capturedRequest.set(modelRequest);
            return ModelResponse.of(new AssistantMessage("ok"));
        });

        assertThat(capturedRequest.get().getMessages()).hasSize(4);
        assertThat(capturedRequest.get().getMessages().get(2)).isSameAs(toolResponseMessage);
    }
}
