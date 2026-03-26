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
}
