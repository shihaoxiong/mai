package com.mai.deerflow.backend.runtime.agent;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.mai.deerflow.backend.runtime.checkpoint.RuntimeCheckpointService;
import com.mai.deerflow.backend.runtime.contract.TodoItem;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 当 todos 仍然存在，但 `write_todos` 已经离开当前上下文窗口时，补一条 reminder。
 */
public class RuntimeTodoReminderInterceptor extends ModelInterceptor {

    private static final String REMINDER_MARKER = "<todo_reminder>";

    private final String threadId;
    private final RuntimeCheckpointService runtimeCheckpointService;
    private final RuntimeAgentEnhancementService runtimeAgentEnhancementService;

    public RuntimeTodoReminderInterceptor(String threadId,
                                          RuntimeCheckpointService runtimeCheckpointService,
                                          RuntimeAgentEnhancementService runtimeAgentEnhancementService) {
        this.threadId = threadId;
        this.runtimeCheckpointService = runtimeCheckpointService;
        this.runtimeAgentEnhancementService = runtimeAgentEnhancementService;
    }

    @Override
    public String getName() {
        return "runtime-todo-reminder-interceptor";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        List<TodoItem> todos = currentTodos();
        if (todos.isEmpty() || todosVisibleInMessages(request.getMessages()) || reminderAlreadyInjected(request.getMessages())) {
            return handler.call(request);
        }

        List<Message> messages = new ArrayList<>(request.getMessages());
        messages.add(0, new SystemMessage(reminderContent(todos)));
        return handler.call(ModelRequest.builder(request)
                .systemMessage(request.getSystemMessage())
                .messages(messages)
                .build());
    }

    private List<TodoItem> currentTodos() {
        return runtimeCheckpointService.leadAgentSaver()
                .get(RunnableConfig.builder().threadId(threadId).build())
                .map(Checkpoint::getState)
                .map(runtimeAgentEnhancementService::extractTodos)
                .orElse(List.of());
    }

    private boolean todosVisibleInMessages(List<Message> messages) {
        return messages.stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .filter(AssistantMessage::hasToolCalls)
                .flatMap(message -> message.getToolCalls().stream())
                .anyMatch(toolCall -> "write_todos".equals(toolCall.name()));
    }

    private boolean reminderAlreadyInjected(List<Message> messages) {
        return messages.stream()
                .filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast)
                .map(SystemMessage::getText)
                .anyMatch(text -> text != null && text.contains(REMINDER_MARKER));
    }

    private String reminderContent(List<TodoItem> todos) {
        StringBuilder builder = new StringBuilder();
        builder.append(REMINDER_MARKER).append('\n');
        builder.append("你当前仍有一个活动中的 todo 列表，虽然原始 write_todos 调用已经不在当前上下文窗口里。\n");
        builder.append("继续工作时，请参考并及时更新这些待办：\n");
        for (TodoItem todo : todos) {
            builder.append("- [")
                    .append(todo.status().name())
                    .append("] ")
                    .append(todo.title())
                    .append('\n');
        }
        return builder.toString().trim();
    }
}
