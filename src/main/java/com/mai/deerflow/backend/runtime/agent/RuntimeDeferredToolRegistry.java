package com.mai.deerflow.backend.runtime.agent;

import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 运行时 deferred tool 注册表。
 */
public class RuntimeDeferredToolRegistry {

    private static final int MAX_RESULTS = 5;

    private final List<RuntimeDeferredToolEntry> entries = new ArrayList<>();

    public void register(ToolCallback tool) {
        entries.add(new RuntimeDeferredToolEntry(
                tool.getToolDefinition().name(),
                tool.getToolDefinition().description(),
                tool
        ));
    }

    public List<RuntimeDeferredToolEntry> entries() {
        return List.copyOf(entries);
    }

    public List<ToolCallback> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        if (query.startsWith("select:")) {
            Set<String> names = new LinkedHashSet<>();
            for (String name : query.substring("select:".length()).split(",")) {
                if (!name.isBlank()) {
                    names.add(name.trim());
                }
            }
            return entries.stream()
                    .filter(entry -> names.contains(entry.name()))
                    .limit(MAX_RESULTS)
                    .map(RuntimeDeferredToolEntry::tool)
                    .toList();
        }

        if (query.startsWith("+")) {
            String[] parts = query.substring(1).trim().split("\\s+", 2);
            String required = parts[0].toLowerCase();
            List<RuntimeDeferredToolEntry> candidates = entries.stream()
                    .filter(entry -> entry.name().toLowerCase().contains(required))
                    .toList();
            if (parts.length == 1) {
                return candidates.stream().limit(MAX_RESULTS).map(RuntimeDeferredToolEntry::tool).toList();
            }

            String rankingQuery = parts[1];
            return candidates.stream()
                    .sorted(Comparator.comparingInt((RuntimeDeferredToolEntry entry) -> regexScore(rankingQuery, entry)).reversed())
                    .limit(MAX_RESULTS)
                    .map(RuntimeDeferredToolEntry::tool)
                    .toList();
        }

        Pattern pattern = compile(query);
        return entries.stream()
                .filter(entry -> pattern.matcher(entry.name() + " " + entry.description()).find())
                .sorted(Comparator.comparingInt((RuntimeDeferredToolEntry entry) -> pattern.matcher(entry.name()).find() ? 2 : 1).reversed())
                .limit(MAX_RESULTS)
                .map(RuntimeDeferredToolEntry::tool)
                .toList();
    }

    private int regexScore(String query, RuntimeDeferredToolEntry entry) {
        Pattern pattern = compile(query);
        String searchable = entry.name() + " " + entry.description();
        int score = 0;
        var matcher = pattern.matcher(searchable);
        while (matcher.find()) {
            score++;
        }
        return score;
    }

    private Pattern compile(String query) {
        try {
            return Pattern.compile(query, Pattern.CASE_INSENSITIVE);
        }
        catch (PatternSyntaxException exception) {
            return Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE);
        }
    }
}
