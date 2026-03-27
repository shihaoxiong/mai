package com.mai.deerflow.backend.runtime.postrun;

import com.mai.deerflow.backend.runtime.contract.ArtifactRef;
import com.mai.deerflow.backend.runtime.contract.TodoItem;
import com.mai.deerflow.backend.runtime.contract.UploadRef;
import com.mai.deerflow.backend.runtime.subtask.SubTaskRecord;
import com.mai.deerflow.backend.runtime.subtask.SubTaskStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Service
/**
 * 运行完成后的轻量 post-run 处理服务。
 *
 * 当前先使用启发式规则生成标题和建议问题，
 * 避免继续依赖硬编码占位值，同时为后续接入专门模型留出稳定边界。
 */
public class PostRunGenerationService {

    private static final int MAX_TITLE_LENGTH = 48;
    private static final int MAX_SUGGESTIONS = 3;

    /**
     * 基于线程上下文生成标题和建议问题。
     */
    public PostRunGenerationResult generate(String userMessage,
                                            String assistantOutput,
                                            List<TodoItem> todos,
                                            List<UploadRef> uploads,
                                            List<ArtifactRef> artifacts,
                                            List<SubTaskRecord> subTasks) {
        String title = generateTitle(userMessage);
        List<String> suggestions = generateSuggestions(userMessage, assistantOutput, todos, uploads, artifacts, subTasks);
        return new PostRunGenerationResult(title, suggestions);
    }

    private String generateTitle(String userMessage) {
        String normalized = normalize(userMessage);
        if (normalized.isBlank()) {
            return "New thread";
        }

        String withoutPolitePrefix = stripPolitePrefix(normalized);
        String trimmed = stripTrailingPunctuation(withoutPolitePrefix);
        if (trimmed.length() <= MAX_TITLE_LENGTH) {
            return trimmed;
        }

        int lastSpaceBeforeLimit = trimmed.lastIndexOf(' ', MAX_TITLE_LENGTH);
        if (lastSpaceBeforeLimit >= 12) {
            return trimmed.substring(0, lastSpaceBeforeLimit).trim();
        }
        return trimmed.substring(0, MAX_TITLE_LENGTH).trim();
    }

    private List<String> generateSuggestions(String userMessage,
                                             String assistantOutput,
                                             List<TodoItem> todos,
                                             List<UploadRef> uploads,
                                             List<ArtifactRef> artifacts,
                                             List<SubTaskRecord> subTasks) {
        String normalizedMessage = normalize(userMessage).toLowerCase(Locale.ROOT);
        String normalizedAssistantOutput = normalize(assistantOutput).toLowerCase(Locale.ROOT);
        LinkedHashSet<String> suggestions = new LinkedHashSet<>();

        if (todos != null && !todos.isEmpty()) {
            suggestions.add("continue with \"" + todos.get(0).title() + "\"");
        }
        if (artifacts != null && !artifacts.isEmpty()) {
            suggestions.add("review the generated artifact \"" + artifacts.get(0).name() + "\"");
        }
        if (subTasks != null && !subTasks.isEmpty()) {
            SubTaskRecord latestSubTask = subTasks.get(subTasks.size() - 1);
            if (latestSubTask.status() == SubTaskStatus.FAILED
                    || latestSubTask.status() == SubTaskStatus.TIMED_OUT
                    || latestSubTask.status() == SubTaskStatus.CANCELLED) {
                suggestions.add("retry the delegated subtask \"" + latestSubTask.title() + "\"");
            }
            else {
                suggestions.add("review the delegated subtask results");
            }
        }
        if (uploads != null && !uploads.isEmpty()) {
            suggestions.add("compare this answer with the uploaded files");
        }

        if (containsAny(normalizedMessage, List.of("analy", "review", "assess", "audit", "风险", "分析", "评审"))) {
            suggestions.add("ask for risks and next steps");
            suggestions.add("request a concise action checklist");
        }
        else if (containsAny(normalizedMessage, List.of("implement", "build", "code", "refactor", "开发", "实现", "编码"))) {
            suggestions.add("ask for an implementation plan");
            suggestions.add("request test cases for this change");
        }
        else if (containsAny(normalizedMessage, List.of("summar", "explain", "总结", "解释"))) {
            suggestions.add("ask for a shorter summary");
            suggestions.add("request a bullet checklist");
        }

        if (normalizedAssistantOutput.contains("parent_observation=")) {
            suggestions.add("ask for a final merged conclusion");
        }

        suggestions.add("continue this thread");

        return suggestions.stream()
                .filter(suggestion -> suggestion != null && !suggestion.isBlank())
                .limit(MAX_SUGGESTIONS)
                .toList();
    }

    private String stripPolitePrefix(String value) {
        return value.replaceFirst("^(please\\s+|could you\\s+|can you\\s+|请\\s*|帮我\\s*|麻烦你\\s*)", "");
    }

    private String stripTrailingPunctuation(String value) {
        return value.replaceFirst("[\\p{Punct}。！？；，、]+$", "").trim();
    }

    private boolean containsAny(String value, List<String> fragments) {
        return fragments.stream().anyMatch(value::contains);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }
}
