package com.mai.deerflow.backend.runtime.agent;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 在真正调用模型前，把线程级文件上下文临时注入到最后一条用户消息里。
 *
 * 设计目标：
 * 1. 模拟 DeerFlow 中 uploads/thread-data middleware 的效果
 * 2. 仅影响当前模型调用，不污染持久化消息历史
 * 3. 让 lead agent 在每轮都能看到当前线程目录与上传文件清单
 */
public class RuntimeThreadContextInterceptor extends ModelInterceptor {

    private final String threadId;
    private final RuntimeLeadAgentPromptService runtimeLeadAgentPromptService;

    public RuntimeThreadContextInterceptor(String threadId,
                                           RuntimeLeadAgentPromptService runtimeLeadAgentPromptService) {
        this.threadId = threadId;
        this.runtimeLeadAgentPromptService = runtimeLeadAgentPromptService;
    }

    @Override
    public String getName() {
        return "runtime-thread-context-interceptor";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        if (alreadyInjected(request.getMessages())) {
            return handler.call(request);
        }

        List<Message> messages = new ArrayList<>(request.getMessages());
        messages.add(0, new SystemMessage(runtimeLeadAgentPromptService.turnContextBlock(threadId)));

        return handler.call(ModelRequest.builder(request)
                .systemMessage(request.getSystemMessage())
                .messages(messages)
                .build());
    }

    private boolean alreadyInjected(List<Message> messages) {
        return messages.stream()
                .filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast)
                .map(SystemMessage::getText)
                .anyMatch(text -> text != null && text.contains("<thread_data>") && text.contains("<uploaded_files>"));
    }
}
