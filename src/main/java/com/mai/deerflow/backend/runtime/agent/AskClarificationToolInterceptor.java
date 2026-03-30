package com.mai.deerflow.backend.runtime.agent;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 在 tool-call 拦截层短路 `ask_clarification`，把它转换成受控中断。
 *
 * 这样可以绕开默认的 tool error processor，避免 agent 把澄清请求当成普通报错继续循环。
 */
public class AskClarificationToolInterceptor extends ToolInterceptor {

    private final ObjectMapper objectMapper;

    public AskClarificationToolInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "ask-clarification-tool-interceptor";
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        if (!"ask_clarification".equals(request.getToolName())) {
            return handler.call(request);
        }

        AskClarificationRequest clarificationRequest = parseRequest(request.getArguments());
        throw new AgentClarificationRequestedException(clarificationRequest);
    }

    private AskClarificationRequest parseRequest(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return new AskClarificationRequest(null, null, null, null);
        }
        try {
            return objectMapper.readValue(arguments, AskClarificationRequest.class);
        }
        catch (Exception exception) {
            throw new IllegalArgumentException("Failed to parse ask_clarification arguments", exception);
        }
    }
}
