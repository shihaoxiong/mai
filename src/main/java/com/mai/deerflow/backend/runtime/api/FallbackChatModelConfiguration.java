package com.mai.deerflow.backend.runtime.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

@Configuration
public class FallbackChatModelConfiguration {

    @Bean
    @ConditionalOnMissingBean(ChatModel.class)
    ChatModel fallbackChatModel() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                List<Message> messages = prompt.getInstructions();
                String lastUserMessage = messages.stream()
                        .filter(UserMessage.class::isInstance)
                        .map(UserMessage.class::cast)
                        .reduce((previous, current) -> current)
                        .map(UserMessage::getText)
                        .orElse("");

                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage("Processed: " + lastUserMessage)
                )));
            }
        };
    }
}
