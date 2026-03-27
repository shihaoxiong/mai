package com.mai.deerflow.backend.runtime.api;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

/**
 * 提供一个可直接复用的回退聊天模型工厂。
 *
 * 该类本身不再注册 Spring Bean，
 * 由 `RuntimeChatModelConfiguration` 决定何时启用 fallback。
 */
public class FallbackChatModelConfiguration {

    /**
     * 构造最小回退模型，保证本地开发和测试链路可运行。
     */
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
