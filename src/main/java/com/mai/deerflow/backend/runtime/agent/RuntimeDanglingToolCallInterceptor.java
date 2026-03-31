package com.mai.deerflow.backend.runtime.agent;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 在真正调用模型前修补 dangling tool-call 历史。
 *
 * 典型场景：
 * - 上一轮 assistant 已经发出 tool calls
 * - 但工具结果因为中断、恢复或异常没有写回对应的 `ToolResponseMessage`
 * - 直接把这段历史送给模型，容易造成消息格式不完整或上下文不一致
 */
public class RuntimeDanglingToolCallInterceptor extends ModelInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(RuntimeDanglingToolCallInterceptor.class);
    private static final String PLACEHOLDER_RESPONSE = "[Tool call was interrupted and did not return a result.]";

    @Override
    public String getName() {
        return "runtime-dangling-tool-call-interceptor";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        List<Message> patchedMessages = patchMessages(request.getMessages());
        if (patchedMessages == request.getMessages()) {
            return handler.call(request);
        }
        return handler.call(ModelRequest.builder(request)
                .messages(patchedMessages)
                .build());
    }

    private List<Message> patchMessages(List<Message> messages) {
        Set<String> completedToolCallIds = existingToolResponseIds(messages);
        List<Message> patchedMessages = new ArrayList<>(messages.size() + 2);
        boolean changed = false;

        for (Message message : messages) {
            patchedMessages.add(message);
            if (!(message instanceof AssistantMessage assistantMessage) || !assistantMessage.hasToolCalls()) {
                continue;
            }

            List<ToolResponseMessage.ToolResponse> missingResponses = missingResponses(assistantMessage, completedToolCallIds);
            if (missingResponses.isEmpty()) {
                continue;
            }

            patchedMessages.add(ToolResponseMessage.builder()
                    .responses(missingResponses)
                    .build());
            completedToolCallIds.addAll(missingResponses.stream().map(ToolResponseMessage.ToolResponse::id).toList());
            changed = true;

            logger.warn("Patched {} dangling tool call(s) before model invocation", missingResponses.size());
        }

        return changed ? List.copyOf(patchedMessages) : messages;
    }

    private Set<String> existingToolResponseIds(List<Message> messages) {
        Set<String> ids = new LinkedHashSet<>();
        for (Message message : messages) {
            if (!(message instanceof ToolResponseMessage toolResponseMessage)) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                if (hasText(response.id())) {
                    ids.add(response.id());
                }
            }
        }
        return ids;
    }

    private List<ToolResponseMessage.ToolResponse> missingResponses(AssistantMessage assistantMessage,
                                                                    Set<String> completedToolCallIds) {
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
            if (!hasText(toolCall.id()) || completedToolCallIds.contains(toolCall.id())) {
                continue;
            }
            responses.add(new ToolResponseMessage.ToolResponse(
                    toolCall.id(),
                    toolCall.name(),
                    PLACEHOLDER_RESPONSE
            ));
        }
        return List.copyOf(responses);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
