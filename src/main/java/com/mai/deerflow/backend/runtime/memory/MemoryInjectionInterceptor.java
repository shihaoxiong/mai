package com.mai.deerflow.backend.runtime.memory;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.Optional;

public class MemoryInjectionInterceptor extends ModelInterceptor {

    private final String userId;

    private final MemoryInjectionService memoryInjectionService;

    public MemoryInjectionInterceptor(String userId, MemoryInjectionService memoryInjectionService) {
        this.userId = userId;
        this.memoryInjectionService = memoryInjectionService;
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        if (alreadyInjected(request.getSystemMessage())) {
            return handler.call(request);
        }

        memoryInjectionService.inject(userId, request.getSystemMessage().getText());

        return handler.call(ModelRequest.builder(request)
                .systemMessage(request.getSystemMessage())
                .build());
    }

    @Override
    public String getName() {
        return "runtime-memory-injection-interceptor";
    }

    private boolean alreadyInjected(SystemMessage systemMessage) {
        return Optional.of(systemMessage).stream()
                .map(SystemMessage::getText)
                .anyMatch(text -> text != null && text.contains("<long-memory>"));
    }
}
