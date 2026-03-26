package com.mai.deerflow.backend.runtime.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

@Service
/**
 * 运行后异步抽取长期记忆的后台任务。
 *
 * 首版先采用轻量的启发式规则抽取用户偏好、事实与当前上下文，
 * 这样本地开发和测试环境无需依赖额外模型，也能为后续记忆注入提供真实数据。
 */
public class MemoryExtractorJob {

    private static final Logger logger = LoggerFactory.getLogger(MemoryExtractorJob.class);
    private static final int MAX_FACTS_PER_RUN = 3;

    private final MemoryStore memoryStore;
    private final Executor executor;

    @Autowired
    public MemoryExtractorJob(MemoryStore memoryStore) {
        this(memoryStore, ForkJoinPool.commonPool());
    }

    /**
     * 允许在测试或特定部署场景下替换异步执行器。
     */
    public MemoryExtractorJob(MemoryStore memoryStore, Executor executor) {
        this.memoryStore = memoryStore;
        this.executor = executor;
    }

    /**
     * 异步调度一次记忆抽取；失败会被吞掉并记录日志，不影响主运行结果。
     */
    public CompletableFuture<List<MemoryFact>> schedule(MemoryExtractionRequest request) {
        if (request == null || !hasText(request.userId()) || !hasText(request.userMessage())) {
            return CompletableFuture.completedFuture(List.of());
        }

        return CompletableFuture.supplyAsync(() -> extractAndStore(request), executor)
                .exceptionally(exception -> {
                    logger.warn("Failed to extract memories for user {}", request.userId(), exception);
                    return List.of();
                });
    }

    private List<MemoryFact> extractAndStore(MemoryExtractionRequest request) {
        List<MemoryFact> extractedFacts = extractFacts(request);
        if (extractedFacts.isEmpty()) {
            return List.of();
        }
        return memoryStore.saveAll(request.userId(), extractedFacts);
    }

    private List<MemoryFact> extractFacts(MemoryExtractionRequest request) {
        String normalizedMessage = normalizeText(request.userMessage());
        if (!hasText(normalizedMessage)) {
            return List.of();
        }

        Set<String> seenKeys = new LinkedHashSet<>();
        memoryStore.list(request.userId(), new MemoryQuery(64, null)).stream()
                .map(this::dedupeKey)
                .forEach(seenKeys::add);

        List<MemoryFact> candidates = new ArrayList<>();
        String preferenceSegment = firstMatchingSegment(normalizedMessage, this::looksLikePreference);
        if (preferenceSegment != null) {
            candidates.add(candidateFact(request, "preference", preferenceSegment, 0.86d));
        }

        String factSegment = firstMatchingSegment(normalizedMessage, this::looksLikeFact);
        if (factSegment != null) {
            candidates.add(candidateFact(request, "fact", factSegment, 0.78d));
        }

        candidates.add(candidateFact(
                request,
                "context",
                "当前任务上下文：" + abbreviate(normalizedMessage, 160),
                0.65d
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

    private MemoryFact candidateFact(MemoryExtractionRequest request,
                                     String category,
                                     String content,
                                     double confidence) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("source", "memory-extractor");
        if (hasText(request.runId())) {
            attributes.put("runId", request.runId().trim());
        }
        if (hasText(request.title())) {
            attributes.put("title", abbreviate(normalizeText(request.title()), 96));
        }

        return new MemoryFact(
                null,
                category,
                normalizeText(content),
                confidence,
                request.threadId(),
                null,
                null,
                attributes
        );
    }

    private String firstMatchingSegment(String text, SegmentPredicate predicate) {
        return splitSegments(text).stream()
                .filter(predicate::matches)
                .findFirst()
                .orElse(null);
    }

    private List<String> splitSegments(String text) {
        return Arrays.stream(text.split("[\\r\\n。！？!?；;.]+"))
                .map(this::normalizeText)
                .filter(this::hasText)
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

    private boolean containsAny(String text, List<String> fragments) {
        return fragments.stream().anyMatch(text::contains);
    }

    private String dedupeKey(MemoryFact fact) {
        return fact.category().trim().toLowerCase(Locale.ROOT) + "::" + fact.content().trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private String abbreviate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }

    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    @FunctionalInterface
    private interface SegmentPredicate {

        boolean matches(String segment);
    }
}
