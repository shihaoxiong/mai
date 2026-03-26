package com.mai.deerflow.backend.runtime.agent;

import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.hook.TokenCounter;
import com.alibaba.cloud.ai.graph.agent.hook.summarization.SummarizationHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.todolist.TodoListInterceptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mai.deerflow.backend.runtime.contract.TodoItem;
import com.mai.deerflow.backend.runtime.contract.TodoStatus;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
public class RuntimeAgentEnhancementService {

    private static final String WRITE_TODOS_TOOL_NAME = "write_todos";

    private final ObjectMapper objectMapper;

    public RuntimeAgentEnhancementService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<Hook> defaultHooks(ChatModel model) {
        return List.of(summarizationHook(model, 4_000, 6, "Conversation summary:\n"));
    }

    public List<Interceptor> defaultInterceptors() {
        return List.of(TodoListInterceptor.builder().build());
    }

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

    public List<TodoItem> extractTodos(Map<String, Object> threadState) {
        if (threadState == null) {
            return List.of();
        }
        Object messagesObject = threadState.get("messages");
        if (!(messagesObject instanceof List<?> messages)) {
            return List.of();
        }
        for (int index = messages.size() - 1; index >= 0; index--) {
            List<TodoItem> parsed = parseTodosFromMessage(messages.get(index));
            if (!parsed.isEmpty()) {
                return parsed;
            }
        }
        return List.of();
    }

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

    private List<TodoItem> parseTodosFromMessage(Object messageObject) {
        if (messageObject instanceof org.springframework.ai.chat.messages.AssistantMessage assistantMessage) {
            return parseTodosFromToolCalls(assistantMessage.getToolCalls());
        }
        if (messageObject instanceof Map<?, ?> messageMap) {
            Object toolCallsObject = messageMap.get("toolCalls");
            if (toolCallsObject instanceof List<?> toolCalls) {
                return parseTodosFromToolCalls(toolCalls);
            }
        }
        return List.of();
    }

    private List<TodoItem> parseTodosFromToolCalls(List<?> toolCalls) {
        for (Object toolCall : toolCalls) {
            String toolName = extractToolCallField(toolCall, "name");
            if (!WRITE_TODOS_TOOL_NAME.equals(toolName)) {
                continue;
            }

            String arguments = extractToolCallField(toolCall, "arguments");
            if (arguments == null || arguments.isBlank()) {
                continue;
            }

            try {
                JsonNode todosNode = objectMapper.readTree(arguments).path("todos");
                if (!todosNode.isArray()) {
                    return List.of();
                }

                List<TodoItem> todos = new ArrayList<>();
                int counter = 1;
                for (JsonNode todoNode : todosNode) {
                    todos.add(new TodoItem(
                            "todo-" + counter++,
                            todoNode.path("content").asText(),
                            toTodoStatus(todoNode.path("status").asText())
                    ));
                }
                return todos;
            }
            catch (Exception exception) {
                return List.of();
            }
        }
        return Collections.emptyList();
    }

    private String extractToolCallField(Object toolCall, String field) {
        if (toolCall instanceof org.springframework.ai.chat.messages.AssistantMessage.ToolCall assistantToolCall) {
            return switch (field) {
                case "name" -> assistantToolCall.name();
                case "arguments" -> assistantToolCall.arguments();
                default -> null;
            };
        }
        if (toolCall instanceof Map<?, ?> toolCallMap) {
            Object value = toolCallMap.get(field);
            return value == null ? null : String.valueOf(value);
        }
        return null;
    }

    private String extractMessageText(Object messageObject) {
        if (messageObject instanceof Message message) {
            return message.getText();
        }
        if (messageObject instanceof Map<?, ?> messageMap) {
            Object text = messageMap.get("text");
            if (text != null) {
                return String.valueOf(text);
            }
            Object textContent = messageMap.get("textContent");
            return textContent == null ? null : String.valueOf(textContent);
        }
        return null;
    }

    private TodoStatus toTodoStatus(String status) {
        return switch (status) {
            case "IN_PROGRESS" -> TodoStatus.IN_PROGRESS;
            case "COMPLETED" -> TodoStatus.COMPLETED;
            case "BLOCKED" -> TodoStatus.BLOCKED;
            default -> TodoStatus.PENDING;
        };
    }
}
