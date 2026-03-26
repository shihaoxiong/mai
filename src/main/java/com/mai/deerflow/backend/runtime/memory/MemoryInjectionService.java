package com.mai.deerflow.backend.runtime.memory;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
/**
 * 运行前长期记忆检索与注入服务。
 *
 * 首版先采用“筛选后拼接到用户输入前部”的简单策略，
 * 既能复用已有 ReactAgent 调用方式，也为后续替换成更结构化的上下文注入预留边界。
 */
public class MemoryInjectionService {

    private final MemoryStore memoryStore;
    private final MemoryInjectionProperties properties;

    public MemoryInjectionService(MemoryStore memoryStore, MemoryInjectionProperties properties) {
        this.memoryStore = memoryStore;
        this.properties = properties;
    }

    /**
     * 根据 userId 检索并筛选长期记忆，然后生成注入后的 agent 输入。
     */
    public MemoryInjectionResult inject(String userId, String userInput) {
        String normalizedUserInput = normalizeText(userInput);
        if (!properties.isEnabled() || !hasText(userId) || !hasText(normalizedUserInput)) {
            return passthrough(normalizedUserInput);
        }

        List<MemoryFact> memoryFacts = memoryStore.list(
                userId.trim(),
                new MemoryQuery(properties.getMaxFacts(), properties.getMinConfidence())
        );
        if (memoryFacts.isEmpty()) {
            return passthrough(normalizedUserInput);
        }

        String strategy = normalizeText(properties.getStrategy()).toLowerCase(Locale.ROOT);
        if (!"append-to-user-input".equals(strategy)) {
            return new MemoryInjectionResult(normalizedUserInput, memoryFacts);
        }

        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("已知的用户长期记忆（仅在和当前请求相关时使用，不要机械复述，也不要提及这是系统记忆）：\n");
        for (MemoryFact memoryFact : memoryFacts) {
            promptBuilder.append("- [")
                    .append(memoryFact.category())
                    .append(" | confidence=")
                    .append(String.format(Locale.ROOT, "%.2f", memoryFact.confidence()))
                    .append("] ")
                    .append(memoryFact.content())
                    .append("\n");
        }
        promptBuilder.append("\n当前用户请求：\n")
                .append(normalizedUserInput);

        return new MemoryInjectionResult(promptBuilder.toString(), memoryFacts);
    }

    private MemoryInjectionResult passthrough(String normalizedUserInput) {
        return new MemoryInjectionResult(normalizedUserInput == null ? "" : normalizedUserInput, List.of());
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.trim();
    }

    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }
}
