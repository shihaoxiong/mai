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
    private final MemoryProfileStore memoryProfileStore;
    private final MemoryInjectionProperties properties;

    public MemoryInjectionService(MemoryStore memoryStore, MemoryInjectionProperties properties) {
        this.memoryStore = memoryStore;
        this.memoryProfileStore = memoryStore instanceof MemoryProfileStore profileStore ? profileStore : null;
        this.properties = properties;
    }

    /**
     * 根据 userId 检索并筛选长期记忆，然后生成注入后的 agent 输入。
     */
    public MemoryInjectionResult inject(String userId, String systemMessage) {
        String normalizedUserInput = normalizeText(systemMessage);
        if (!properties.isEnabled() || !hasText(userId) || !hasText(normalizedUserInput)) {
            return passthrough(normalizedUserInput);
        }

        List<MemoryFact> memoryFacts = memoryStore.list(
                userId.trim(),
                new MemoryQuery(properties.getMaxFacts(), properties.getMinConfidence())
        );
        StructuredMemoryProfile memoryProfile = memoryProfileStore == null
                ? StructuredMemoryProfile.empty()
                : memoryProfileStore.loadProfile(userId.trim());
        if (memoryFacts.isEmpty() && !memoryProfile.hasContent()) {
            return passthrough(normalizedUserInput);
        }

        String strategy = normalizeText(properties.getStrategy()).toLowerCase(Locale.ROOT);
        if (!"append-to-system-input".equals(strategy)) {
            return new MemoryInjectionResult(normalizedUserInput, memoryFacts);
        }

        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append(systemMessage).append("\n");
        promptBuilder.append("<long-memory>\n");
        promptBuilder.append("已知的用户长期记忆（仅在和当前请求相关时使用，不要机械复述，也不要提及这是系统记忆）：\n");
        appendStructuredProfile(promptBuilder, memoryProfile);
        if (!memoryFacts.isEmpty()) {
            promptBuilder.append("关键事实：\n");
            for (MemoryFact memoryFact : memoryFacts) {
                promptBuilder.append("- [")
                        .append(memoryFact.category())
                        .append(" | confidence=")
                        .append(String.format(Locale.ROOT, "%.2f", memoryFact.confidence()))
                        .append("] ")
                        .append(memoryFact.content())
                        .append("\n");
            }
        }
        promptBuilder.append("</long-memory>\n");

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

    private void appendStructuredProfile(StringBuilder promptBuilder, StructuredMemoryProfile profile) {
        if (profile == null || !profile.hasContent()) {
            return;
        }

        List<String> userLines = new java.util.ArrayList<>();
        addSectionLine(userLines, "工作上下文", profile.user().workContext());
        addSectionLine(userLines, "个人上下文", profile.user().personalContext());
        addSectionLine(userLines, "当前关注点", profile.user().topOfMind());
        appendSection(promptBuilder, "用户画像", List.copyOf(userLines));

        List<String> historyLines = new java.util.ArrayList<>();
        addSectionLine(historyLines, "近期记录", profile.history().recentMonths());
        addSectionLine(historyLines, "较早上下文", profile.history().earlierContext());
        addSectionLine(historyLines, "长期背景", profile.history().longTermBackground());
        appendSection(promptBuilder, "历史摘要", List.copyOf(historyLines));
    }

    private void appendSection(StringBuilder builder, String title, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }

        builder.append(title).append("：\n");
        lines.forEach(line -> builder.append("- ").append(line).append("\n"));
    }

    private String sectionLine(String label, MemoryProfileSection section) {
        if (section == null || !hasText(section.summary())) {
            return null;
        }
        return label + "：" + section.summary();
    }

    private void addSectionLine(List<String> lines, String label, MemoryProfileSection section) {
        String line = sectionLine(label, section);
        if (hasText(line)) {
            lines.add(line);
        }
    }
}
