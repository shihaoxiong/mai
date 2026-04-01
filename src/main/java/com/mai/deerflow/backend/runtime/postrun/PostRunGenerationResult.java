package com.mai.deerflow.backend.runtime.postrun;

import java.util.List;

/**
 * post-run 处理阶段输出的标题和建议问题。
 */
public record PostRunGenerationResult(
        String title,
        List<String> suggestions
) {

    public PostRunGenerationResult {
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }
}
