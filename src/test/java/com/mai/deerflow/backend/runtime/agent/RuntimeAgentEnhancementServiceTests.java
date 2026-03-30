package com.mai.deerflow.backend.runtime.agent;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mai.deerflow.backend.runtime.contract.TodoItem;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeAgentEnhancementServiceTests {

    private final RuntimeAgentEnhancementService runtimeAgentEnhancementService =
            new RuntimeAgentEnhancementService(new ObjectMapper());
    private final LeadAgentFactory leadAgentFactory = new LeadAgentFactory();

    @Test
    void todoListInterceptorShouldEnableWriteTodosToolFlow() throws Exception {
        ChatModel planningModel = new PlanningChatModel();

        var agent = leadAgentFactory.create(LeadAgentDefinition.builder(planningModel)
                .name("planning-agent")
                .instruction("Plan the work.")
                .interceptors(runtimeAgentEnhancementService.defaultInterceptors())
                .saver(new MemorySaver())
                .build());

        RunnableConfig config = RunnableConfig.builder().threadId("planning-thread").build();
        AssistantMessage assistantMessage = agent.call("plan this work", config);

        List<TodoItem> todos = runtimeAgentEnhancementService.extractTodos(
                agent.getCompiledGraph().getState(config).state().data()
        );

        assertThat(assistantMessage.getText()).isEqualTo("planning-finished");
        assertThat(todos).extracting(TodoItem::title)
                .containsExactly("Read uploaded brief", "Draft summary");
    }

    @Test
    void summarizationHookShouldInsertSummaryMessageWhenThresholdIsExceeded() throws Exception {
        ChatModel summaryModel = new SummaryAwareChatModel();

        var agent = leadAgentFactory.create(LeadAgentDefinition.builder(summaryModel)
                .name("summary-agent")
                .instruction("Summarize older messages when needed.")
                .hooks(List.of(runtimeAgentEnhancementService.summarizationHook(
                        summaryModel,
                        1,
                        1,
                        "Conversation summary:\n"
                )))
                .saver(new MemorySaver())
                .build());

        RunnableConfig config = RunnableConfig.builder().threadId("summary-thread").build();
        agent.call("first long message", config);
        agent.call("second long message", config);

        List<String> messageTexts = runtimeAgentEnhancementService.extractMessageTexts(
                agent.getCompiledGraph().getState(config).state().data()
        );

        assertThat(messageTexts).anyMatch(text -> text.contains("Conversation summary:"));
    }

    @Test
    void toolCallSafetyInterceptorShouldTruncateExcessTaskCalls() throws Exception {
        AtomicInteger taskInvocationCount = new AtomicInteger();
        var taskTool = org.springframework.ai.tool.function.FunctionToolCallback
                .builder("task", (TaskRequest request) -> {
                    taskInvocationCount.incrementAndGet();
                    return "task-result:" + request.title();
                })
                .description("Synthetic task tool for testing.")
                .inputType(TaskRequest.class)
                .build();

        var agent = leadAgentFactory.create(LeadAgentDefinition.builder(new ManyTaskCallsChatModel())
                .name("task-limit-agent")
                .instruction("Limit task fan-out.")
                .tools(List.of(taskTool))
                .interceptors(runtimeAgentEnhancementService.defaultInterceptors())
                .saver(new MemorySaver())
                .build());

        AssistantMessage assistantMessage = agent.call("launch many tasks");

        assertThat(taskInvocationCount.get()).isEqualTo(3);
        assertThat(assistantMessage.getText()).isEqualTo("taskResponses=3");
    }

    @Test
    void toolCallSafetyInterceptorShouldForceStopRepeatedToolLoops() throws Exception {
        AtomicInteger echoInvocationCount = new AtomicInteger();
        var echoTool = org.springframework.ai.tool.function.FunctionToolCallback
                .builder("echo", (EchoRequest request) -> {
                    echoInvocationCount.incrementAndGet();
                    return "echo:" + request.value();
                })
                .description("Synthetic loop tool for testing.")
                .inputType(EchoRequest.class)
                .build();

        var agent = leadAgentFactory.create(LeadAgentDefinition.builder(new RepeatingToolLoopChatModel())
                .name("loop-guard-agent")
                .instruction("Stop repeated tool loops.")
                .tools(List.of(echoTool))
                .interceptors(runtimeAgentEnhancementService.defaultInterceptors())
                .saver(new MemorySaver())
                .build());

        AssistantMessage assistantMessage = agent.call("repeat the same tool call");

        assertThat(echoInvocationCount.get()).isEqualTo(4);
        assertThat(assistantMessage.getText()).contains(RuntimeToolCallSafetyInterceptor.FORCED_STOP_MESSAGE);
    }

    record TaskRequest(String title) {
    }

    record EchoRequest(String value) {
    }

    private static final class PlanningChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            boolean hasToolResponse = messages.stream().anyMatch(ToolResponseMessage.class::isInstance);

            if (!hasToolResponse) {
                AssistantMessage toolCall = AssistantMessage.builder()
                        .content("Planning work")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "todo-call-1",
                                "function",
                                "write_todos",
                                """
                                        {
                                          "todos": [
                                            {"content": "Read uploaded brief", "status": "IN_PROGRESS"},
                                            {"content": "Draft summary", "status": "PENDING"}
                                          ]
                                        }
                                        """
                        )))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCall)));
            }

            return new ChatResponse(List.of(new Generation(new AssistantMessage("planning-finished"))));
        }
    }

    private static final class SummaryAwareChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            boolean hasSummaryMessage = messages.stream()
                    .filter(SystemMessage.class::isInstance)
                    .map(SystemMessage.class::cast)
                    .map(SystemMessage::getText)
                    .anyMatch(text -> text.contains("Conversation summary:"));

            String lastUserMessage = messages.stream()
                    .filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast)
                    .reduce((previous, current) -> current)
                    .map(UserMessage::getText)
                    .orElse("");

            String response = hasSummaryMessage
                    ? "summary-observed:" + lastUserMessage
                    : "plain-response:" + lastUserMessage;

            return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
        }
    }

    private static final class ManyTaskCallsChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            List<ToolResponseMessage> toolResponses = messages.stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .toList();

            if (toolResponses.isEmpty()) {
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .content("Launch four tasks")
                        .toolCalls(List.of(
                                new AssistantMessage.ToolCall("task-1", "function", "task", "{\"title\":\"one\"}"),
                                new AssistantMessage.ToolCall("task-2", "function", "task", "{\"title\":\"two\"}"),
                                new AssistantMessage.ToolCall("task-3", "function", "task", "{\"title\":\"three\"}"),
                                new AssistantMessage.ToolCall("task-4", "function", "task", "{\"title\":\"four\"}")
                        ))
                        .build())));
            }

            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "taskResponses=" + toolResponses.get(0).getResponses().size()
            ))));
        }
    }

    private static final class RepeatingToolLoopChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            List<AssistantMessage> assistantMessages = messages.stream()
                    .filter(AssistantMessage.class::isInstance)
                    .map(AssistantMessage.class::cast)
                    .toList();
            AssistantMessage lastAssistantMessage = assistantMessages.isEmpty()
                    ? null
                    : assistantMessages.get(assistantMessages.size() - 1);
            if (lastAssistantMessage != null
                    && !lastAssistantMessage.hasToolCalls()
                    && lastAssistantMessage.getText() != null
                    && lastAssistantMessage.getText().contains(RuntimeToolCallSafetyInterceptor.FORCED_STOP_MESSAGE)) {
                return new ChatResponse(List.of(new Generation(lastAssistantMessage)));
            }

            return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                    .content("Repeat same tool call")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "echo-repeat",
                            "function",
                            "echo",
                            "{\"value\":\"same\"}"
                    )))
                    .build())));
        }
    }
}
