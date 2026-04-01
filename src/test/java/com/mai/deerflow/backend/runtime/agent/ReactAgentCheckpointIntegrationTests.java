package com.mai.deerflow.backend.runtime.agent;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReactAgentCheckpointIntegrationTests {

    private final ReactAgent reactAgent = ReactAgent.builder()
            .name("react-agent-checkpoint-test")
            .instruction("You are a checkpoint verification agent.")
            .model(new PromptCountingChatModel())
            .saver(new MemorySaver())
            .releaseThread(false)
            .build();

    @Test
    void sameThreadShouldReuseConversationHistory() throws Exception {
        RunnableConfig runnableConfig = RunnableConfig.builder()
                .threadId("thread-alpha")
                .build();

        AssistantMessage firstReply = reactAgent.call("hello deerflow", runnableConfig);
        AssistantMessage secondReply = reactAgent.call("follow up question", runnableConfig);

        assertThat(firstReply.getText())
                .contains("userMessages=1")
                .contains("assistantMessages=0")
                .contains("lastUser=hello deerflow");

        assertThat(secondReply.getText())
                .contains("userMessages=2")
                .contains("assistantMessages=1")
                .contains("lastUser=follow up question");
    }

    @Test
    void differentThreadsShouldKeepIndependentHistories() throws Exception {
        reactAgent.call("seed first thread", RunnableConfig.builder().threadId("thread-one").build());

        AssistantMessage secondThreadReply = reactAgent.call(
                "fresh thread",
                RunnableConfig.builder().threadId("thread-two").build()
        );

        assertThat(secondThreadReply.getText())
                .contains("userMessages=1")
                .contains("assistantMessages=0")
                .contains("lastUser=fresh thread");
    }

    private static final class PromptCountingChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            long userMessages = messages.stream()
                    .filter(UserMessage.class::isInstance)
                    .count();
            long assistantMessages = messages.stream()
                    .filter(AssistantMessage.class::isInstance)
                    .count();

            String lastUserMessage = messages.stream()
                    .filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast)
                    .reduce((previous, current) -> current)
                    .map(UserMessage::getText)
                    .orElse("N/A");

            String content = "userMessages=%d; assistantMessages=%d; lastUser=%s"
                    .formatted(userMessages, assistantMessages, lastUserMessage);

            return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
        }
    }
}
