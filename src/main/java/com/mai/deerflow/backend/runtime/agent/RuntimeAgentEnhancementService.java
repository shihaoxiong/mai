package com.mai.deerflow.backend.runtime.agent;

import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.hook.TokenCounter;
import com.alibaba.cloud.ai.graph.agent.hook.summarization.SummarizationHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.todolist.TodoListInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mai.deerflow.backend.runtime.contract.TodoItem;
import com.mai.deerflow.backend.runtime.contract.TodoStatus;
import com.mai.deerflow.backend.runtime.contract.ThreadMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
/**
 * 统一封装 runtime 默认使用的 agent 增强能力。
 *
 * 当前主要负责：
 * 1. 构造摘要 hook
 * 2. 构造 todo 拦截器
 * 3. 从 lead agent 的内部状态里提取待办与消息文本
 */
public class RuntimeAgentEnhancementService {

    private final ObjectMapper objectMapper;

    public RuntimeAgentEnhancementService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 返回 runtime lead agent 默认启用的 hook 列表。
     */
    public List<Hook> defaultHooks(ChatModel model) {
        return List.of(summarizationHook(model, 4_000, 6, "Conversation summary:\n"));
    }

    /**
     * 返回 runtime lead agent 默认启用的 interceptor 列表。
     */
    public List<Interceptor> defaultInterceptors() {
        return List.of(
                TodoListInterceptor.builder().build(),
                new AskClarificationToolInterceptor(objectMapper)
        );
    }

    /**
     * 创建摘要 hook。
     */
    public SummarizationHook summarizationHook(ChatModel model,
                                               int maxTokensBeforeSummary,
                                               int messagesToKeep,
                                               String summaryPrefix) {
        return SummarizationHook.builder()
                .model(model)
                .maxTokensBeforeSummary(maxTokensBeforeSummary)
                .messagesToKeep(messagesToKeep)
                .summaryPrefix(summaryPrefix)
                .tokenCounter(TokenCounter.approximateMsgCounter())
                .keepFirstUserMessage(true)
                .build();
    }

    /**
     * 直接从 agent 内部线程状态中的 `todos` 字段提取待办列表。
     */
    public List<TodoItem> extractTodos(Map<String, Object> threadState) {
        if (threadState == null) {
            return List.of();
        }
        Object todosObject = threadState.get("todos");
        if (!(todosObject instanceof List<?> todos)) {
            return List.of();
        }
        return todos.stream()
                .map(this::toTodoItem)
                .toList();
    }

    /**
     * 从 agent 内部线程状态中提取轻量消息视图，供 runtime API 返回。
     */
    public List<ThreadMessage> extractMessages(Map<String, Object> threadState) {
        if (threadState == null) {
            return List.of();
        }
        Object messagesObject = threadState.get("messages");
        if (!(messagesObject instanceof List<?> messages)) {
            return List.of();
        }

        List<ThreadMessage> extracted = new ArrayList<>();
        for (Object messageObject : messages) {
            ThreadMessage message = toThreadMessage(messageObject);
            if (message != null && message.content() != null && !message.content().isBlank()) {
                extracted.add(message);
            }
        }
        return extracted;
    }

    /**
     * 提取线程状态里可见的消息文本，主要用于验证摘要 hook 是否生效。
     */
    public List<String> extractMessageTexts(Map<String, Object> threadState) {
        if (threadState == null) {
            return List.of();
        }
        Object messagesObject = threadState.get("messages");
        if (!(messagesObject instanceof List<?> messages)) {
            return List.of();
        }

        List<String> texts = new ArrayList<>();
        for (Object message : messages) {
            String text = extractMessageText(message);
            if (text != null && !text.isBlank()) {
                texts.add(text);
            }
        }
        return texts;
    }

    private String extractMessageText(Object messageObject) {
        if (messageObject instanceof Message message) {
            if (message instanceof ToolResponseMessage toolResponseMessage) {
                return toolResponseMessage.getResponses().stream()
                        .map(ToolResponseMessage.ToolResponse::responseData)
                        .reduce((left, right) -> left + "\n" + right)
                        .orElse("");
            }
            return message.getText();
        }
        if (messageObject instanceof Map<?, ?> messageMap) {
            Object text = messageMap.get("text");
            if (text != null) {
                return String.valueOf(text);
            }
            Object textContent = messageMap.get("textContent");
            if (textContent != null) {
                return String.valueOf(textContent);
            }
            Object responses = messageMap.get("responses");
            if (responses instanceof List<?> responseList) {
                return responseList.stream()
                        .map(this::extractToolResponseData)
                        .filter(value -> value != null && !value.isBlank())
                        .reduce((left, right) -> left + "\n" + right)
                        .orElse(null);
            }
            return null;
        }
        return null;
    }

    private TodoItem toTodoItem(Object todoObject) {
        Map<?, ?> todoMap = objectMapper.convertValue(todoObject, Map.class);
        String title = textValue(todoMap.get("title"));
        if (title == null || title.isBlank()) {
            title = textValue(todoMap.get("content"));
        }

        String id = textValue(todoMap.get("id"));
        if (id == null || id.isBlank()) {
            id = "todo-" + Math.abs(todoMap.hashCode());
        }

        return new TodoItem(
                id,
                title == null ? "" : title,
                toTodoStatus(textValue(todoMap.get("status")))
        );
    }

    private String textValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private TodoStatus toTodoStatus(String status) {
        if (status == null || status.isBlank()) {
            return TodoStatus.PENDING;
        }
        return switch (status.trim().toUpperCase().replace('-', '_')) {
            case "IN_PROGRESS" -> TodoStatus.IN_PROGRESS;
            case "COMPLETED" -> TodoStatus.COMPLETED;
            case "BLOCKED" -> TodoStatus.BLOCKED;
            default -> TodoStatus.PENDING;
        };
    }

    private ThreadMessage toThreadMessage(Object messageObject) {
        if (messageObject instanceof Message message) {
            return new ThreadMessage(
                    normalizeRole(message.getMessageType().name()),
                    extractMessageText(messageObject)
            );
        }
        if (messageObject instanceof Map<?, ?> messageMap) {
            Object rawRole = messageMap.get("messageType");
            if (rawRole == null) {
                rawRole = messageMap.get("type");
            }
            return new ThreadMessage(
                    normalizeRole(rawRole == null ? "unknown" : String.valueOf(rawRole)),
                    extractMessageText(messageObject)
            );
        }
        return null;
    }

    private String normalizeRole(String rawRole) {
        return rawRole == null ? "unknown" : rawRole.trim().toLowerCase(Locale.ROOT);
    }

    private String extractToolResponseData(Object responseObject) {
        if (responseObject instanceof ToolResponseMessage.ToolResponse toolResponse) {
            return toolResponse.responseData();
        }
        if (responseObject instanceof Map<?, ?> responseMap) {
            Object responseData = responseMap.get("responseData");
            return responseData == null ? null : String.valueOf(responseData);
        }
        return null;
    }

}
