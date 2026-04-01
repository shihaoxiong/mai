package com.mai.deerflow.backend.runtime.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 把一次 run 的会话上下文更新为“结构化 profile + facts”的长期记忆形态。
 *
 * 首版仍使用启发式规则，避免引入额外模型依赖；
 * 但输出不再停留在 facts-only，而是同步维护接近 DeerFlow 的结构化记忆档案。
 */
public class StructuredMemoryUpdater {

    private static final int MAX_FACTS_PER_RUN = 4;
    private static final int MAX_SECTION_ITEMS = 4;

    private final MemoryStore memoryStore;
    private final MemoryProfileStore memoryProfileStore;

    public StructuredMemoryUpdater(MemoryStore memoryStore, MemoryProfileStore memoryProfileStore) {
        this.memoryStore = memoryStore;
        this.memoryProfileStore = memoryProfileStore;
    }

    public List<MemoryFact> update(MemoryExtractionRequest request) {
        String normalizedUserId = normalizeOptionalText(request == null ? null : request.userId());
        String normalizedMessage = sanitizeForMemory(request == null ? null : request.userMessage());
        if (normalizedUserId == null || normalizedMessage == null) {
            return List.of();
        }

        List<MemoryFact> extractedFacts = extractFacts(request, normalizedUserId, normalizedMessage);
        List<MemoryFact> persistedFacts = extractedFacts.isEmpty()
                ? List.of()
                : memoryStore.saveAll(normalizedUserId, extractedFacts);

        StructuredMemoryProfile updatedProfile = rebuildProfile(request, normalizedUserId, normalizedMessage);
        memoryProfileStore.saveProfile(normalizedUserId, updatedProfile);
        return persistedFacts;
    }

    private List<MemoryFact> extractFacts(MemoryExtractionRequest request,
                                          String userId,
                                          String normalizedMessage) {
        Set<String> seenKeys = new LinkedHashSet<>();
        memoryStore.list(userId, new MemoryQuery(128, null)).stream()
                .map(this::dedupeKey)
                .forEach(seenKeys::add);

        List<MemoryFact> candidates = new ArrayList<>();
        String preferenceSegment = firstMatchingSegment(normalizedMessage, this::looksLikePreference);
        if (preferenceSegment != null) {
            candidates.add(candidateFact(request, "preference", preferenceSegment, 0.90d));
        }

        String factSegment = firstMatchingSegment(normalizedMessage, this::looksLikeFact);
        if (factSegment != null) {
            candidates.add(candidateFact(request, "fact", factSegment, 0.82d));
        }

        candidates.add(candidateFact(
                request,
                "context",
                "当前任务上下文：" + abbreviate(normalizedMessage, 180),
                0.68d
        ));

        List<MemoryFact> extractedFacts = new ArrayList<>();
        for (MemoryFact candidate : candidates) {
            if (!seenKeys.add(dedupeKey(candidate))) {
                continue;
            }
            extractedFacts.add(candidate);
            if (extractedFacts.size() >= MAX_FACTS_PER_RUN) {
                break;
            }
        }
        return List.copyOf(extractedFacts);
    }

    private StructuredMemoryProfile rebuildProfile(MemoryExtractionRequest request,
                                                   String userId,
                                                   String normalizedMessage) {
        StructuredMemoryProfile existingProfile = memoryProfileStore.loadProfile(userId);
        List<MemoryFact> allFacts = memoryStore.list(userId, new MemoryQuery(32, null));
        Instant now = Instant.now();
        String nowText = now.toString();

        MemoryProfileSection workContext = section(
                summarizeFacts(allFacts, List.of("fact", "context"), this::looksLikeWorkContext, existingProfile.user().workContext().summary()),
                nowText
        );
        MemoryProfileSection personalContext = section(
                summarizeFacts(allFacts, List.of("fact"), this::looksLikePersonalContext, existingProfile.user().personalContext().summary()),
                nowText
        );
        MemoryProfileSection topOfMind = section(
                recentSummaryFrom(request, normalizedMessage),
                nowText
        );

        MemoryProfileSection recentMonths = section(
                recentSummaryFrom(request, normalizedMessage),
                nowText
        );
        MemoryProfileSection earlierContext = section(
                summarizeFacts(allFacts, List.of("context"), fact -> fact.content().startsWith("当前任务上下文："), existingProfile.history().earlierContext().summary()),
                nowText
        );
        MemoryProfileSection longTermBackground = section(
                summarizeFacts(allFacts, List.of("preference", "fact"), fact -> true, existingProfile.history().longTermBackground().summary()),
                nowText
        );

        return new StructuredMemoryProfile(
                existingProfile.version(),
                nowText,
                new MemoryUserProfile(workContext, personalContext, topOfMind),
                new MemoryHistoryProfile(recentMonths, earlierContext, longTermBackground)
        );
    }

    private MemoryProfileSection section(String summary, String updatedAt) {
        String normalizedSummary = normalizeOptionalText(summary);
        return new MemoryProfileSection(normalizedSummary == null ? "" : normalizedSummary, normalizedSummary == null ? "" : updatedAt);
    }

    private String summarizeFacts(List<MemoryFact> facts,
                                  List<String> categories,
                                  java.util.function.Predicate<MemoryFact> predicate,
                                  String fallback) {
        String summary = facts.stream()
                .filter(fact -> categories.contains(fact.category()))
                .filter(predicate)
                .map(MemoryFact::content)
                .map(this::normalizeOptionalText)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .limit(MAX_SECTION_ITEMS)
                .collect(Collectors.joining("\n- ", "- ", ""));

        if (summary.isBlank()) {
            return normalizeOptionalText(fallback);
        }
        return summary;
    }

    private MemoryFact candidateFact(MemoryExtractionRequest request,
                                     String category,
                                     String content,
                                     double confidence) {
        return new MemoryFact(
                null,
                category,
                content,
                confidence,
                request.threadId(),
                null,
                null,
                Map.of(
                        "source", "structured-memory-updater",
                        "runId", normalizeOptionalText(request.runId()) == null ? "" : request.runId().trim()
                )
        );
    }

    private String recentSummaryFrom(MemoryExtractionRequest request, String normalizedMessage) {
        String title = sanitizeForMemory(request == null ? null : request.title());
        String assistantOutput = sanitizeForMemory(request == null ? null : request.assistantOutput());
        StringBuilder builder = new StringBuilder();
        if (title != null) {
            builder.append(title);
        }
        if (assistantOutput != null) {
            if (builder.length() > 0) {
                builder.append("；");
            }
            builder.append(abbreviate(assistantOutput, 120));
        }
        if (builder.length() == 0) {
            builder.append(abbreviate(normalizedMessage, 120));
        }
        return builder.toString();
    }

    private String firstMatchingSegment(String text, SegmentPredicate predicate) {
        return splitSegments(text).stream()
                .filter(predicate::matches)
                .findFirst()
                .orElse(null);
    }

    private List<String> splitSegments(String text) {
        return Arrays.stream(text.split("[\\r\\n。！？!?；;.]+"))
                .map(this::normalizeOptionalText)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    private boolean looksLikePreference(String segment) {
        String lowerCaseSegment = segment.toLowerCase(Locale.ROOT);
        return lowerCaseSegment.contains("prefer")
                || lowerCaseSegment.contains("please")
                || lowerCaseSegment.contains("do not")
                || lowerCaseSegment.contains("don't")
                || containsAny(segment, List.of("请", "希望", "偏好", "喜欢", "尽量", "优先", "最好", "不要", "别"));
    }

    private boolean looksLikeFact(String segment) {
        String lowerCaseSegment = segment.toLowerCase(Locale.ROOT);
        return lowerCaseSegment.contains("i am")
                || lowerCaseSegment.contains("i'm")
                || lowerCaseSegment.contains("i use")
                || lowerCaseSegment.contains("i work on")
                || lowerCaseSegment.contains("we use")
                || containsAny(segment, List.of("我是", "我用", "我在", "我负责", "我的", "我们用", "我们在"));
    }

    private boolean looksLikeWorkContext(MemoryFact fact) {
        return containsAny(fact.content(), List.of("Java", "backend", "项目", "代码", "开发", "系统", "服务", "线程", "任务"));
    }

    private boolean looksLikePersonalContext(MemoryFact fact) {
        return containsAny(fact.content(), List.of("我是", "I am", "I'm", "我的", "prefer", "喜欢"));
    }

    private boolean containsAny(String text, List<String> fragments) {
        return fragments.stream().anyMatch(text::contains);
    }

    private String sanitizeForMemory(String text) {
        String normalized = normalizeOptionalText(text);
        if (normalized == null) {
            return null;
        }
        String sanitized = normalized
                .replaceAll("<uploaded_files>[\\s\\S]*?</uploaded_files>\\s*", "")
                .replaceAll("<thread_data>[\\s\\S]*?</thread_data>\\s*", "")
                .trim();
        return sanitized.isBlank() ? null : sanitized;
    }

    private String normalizeOptionalText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private String abbreviate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }

    private String dedupeKey(MemoryFact fact) {
        return fact.category().trim().toLowerCase(Locale.ROOT) + "::" + fact.content().trim().toLowerCase(Locale.ROOT);
    }

    @FunctionalInterface
    private interface SegmentPredicate {

        boolean matches(String segment);
    }
}
