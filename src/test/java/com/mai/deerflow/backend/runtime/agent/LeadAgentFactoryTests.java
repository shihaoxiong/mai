package com.mai.deerflow.backend.runtime.agent;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LeadAgentFactoryTests {

    private final LeadAgentFactory leadAgentFactory = new LeadAgentFactory();

    @Test
    void shouldCreateAgentThatPreservesThreadHistory() throws Exception {
        LeadAgentDefinition definition = LeadAgentDefinition.builder(new PromptCountingChatModel())
                .name("lead-agent")
                .instruction("Track conversation history.")
                .saver(new MemorySaver())
                .releaseThread(false)
                .build();

        ReactAgent reactAgent = leadAgentFactory.create(definition);
        RunnableConfig runnableConfig = RunnableConfig.builder().threadId("lead-thread").build();

        AssistantMessage first = reactAgent.call("hello", runnableConfig);
        AssistantMessage second = reactAgent.call("follow up", runnableConfig);

        assertThat(reactAgent.instruction()).isEqualTo("Track conversation history.");
        assertThat(first.getText()).contains("userMessages=1").contains("assistantMessages=0");
        assertThat(second.getText()).contains("userMessages=2").contains("assistantMessages=1");
    }

    @Test
    void shouldCreateAgentWithConfiguredTools() throws Exception {
        ToolCallback localTool = FunctionToolCallback
                .builder("local_upper", (UppercaseInput input) -> "UPPER:" + input.text().toUpperCase())
                .description("Uppercase text through a local tool.")
                .inputType(UppercaseInput.class)
                .build();

        LeadAgentDefinition definition = LeadAgentDefinition.builder(new ToolCallingChatModel())
                .name("tool-agent")
                .instruction("Call the provided local tool.")
                .tools(List.of(localTool))
                .build();

        ReactAgent reactAgent = leadAgentFactory.create(definition);
        AssistantMessage assistantMessage = reactAgent.call("use the tool");

        assertThat(assistantMessage.getText()).contains("UPPER:ALPHA");
    }

    record UppercaseInput(String text) {
    }

    private static final class PromptCountingChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            long userMessages = messages.stream().filter(UserMessage.class::isInstance).count();
            long assistantMessages = messages.stream().filter(AssistantMessage.class::isInstance).count();

            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "userMessages=%d; assistantMessages=%d".formatted(userMessages, assistantMessages)
            ))));
        }
    }

    private static final class ToolCallingChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            List<ToolResponseMessage> toolResponses = messages.stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .toList();

            if (toolResponses.isEmpty()) {
                AssistantMessage toolCallMessage = AssistantMessage.builder()
                        .content("Calling uppercase tool")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-upper",
                                "function",
                                "local_upper",
                                "{\"text\":\"alpha\"}"
                        )))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCallMessage)));
            }

            String toolResult = toolResponses.get(0).getResponses().get(0).responseData();
            return new ChatResponse(List.of(new Generation(new AssistantMessage("toolResult=" + toolResult))));
        }
    }
}
