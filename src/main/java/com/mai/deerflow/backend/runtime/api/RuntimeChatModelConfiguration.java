package com.mai.deerflow.backend.runtime.api;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

@Configuration
/**
 * 统一选择 runtime 使用的主 ChatModel。
 *
 * 规则：
 * 1. 若存在唯一外部 ChatModel Bean，直接复用
 * 2. 若存在多个外部 ChatModel，优先复用唯一的 primary Bean
 * 3. 若没有外部 ChatModel，则回退到本地 fallback 模型
 */
public class RuntimeChatModelConfiguration {

    private static final String RUNTIME_CHAT_MODEL_BEAN_NAME = "runtimeChatModel";

    @Bean(name = RUNTIME_CHAT_MODEL_BEAN_NAME)
    ChatModel runtimeChatModel(ConfigurableListableBeanFactory beanFactory) {
        List<String> candidateBeanNames = Arrays.stream(beanFactory.getBeanNamesForType(ChatModel.class, false, false))
                .filter(beanName -> !RUNTIME_CHAT_MODEL_BEAN_NAME.equals(beanName))
                .toList();

        if (candidateBeanNames.isEmpty()) {
            return new FallbackChatModelConfiguration().fallbackChatModel();
        }

        if (candidateBeanNames.size() == 1) {
            return resolveOrFallback(beanFactory, candidateBeanNames.get(0));
        }

        List<String> primaryBeanNames = candidateBeanNames.stream()
                .filter(beanFactory::containsBeanDefinition)
                .filter(beanName -> beanFactory.getBeanDefinition(beanName).isPrimary())
                .toList();

        if (primaryBeanNames.size() == 1) {
            return resolveOrFallback(beanFactory, primaryBeanNames.get(0));
        }

        throw new IllegalStateException("Multiple ChatModel beans found: " + candidateBeanNames
                + ". Please keep only one model bean or mark exactly one as primary.");
    }

    private ChatModel resolveOrFallback(ConfigurableListableBeanFactory beanFactory, String beanName) {
        try {
            return beanFactory.getBean(beanName, ChatModel.class);
        }
        catch (BeansException exception) {
            return new FallbackChatModelConfiguration().fallbackChatModel();
        }
    }
}
