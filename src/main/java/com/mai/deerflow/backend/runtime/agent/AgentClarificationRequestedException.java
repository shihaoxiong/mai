package com.mai.deerflow.backend.runtime.agent;

import java.util.List;

/**
 * 用于在 `ask_clarification` 工具被调用时，受控地中断当前 lead agent 执行。
 *
 * 该异常会在 `ThreadRuntimeService` 中被转换成
 * `WAITING_CLARIFICATION + PendingApproval` 状态，而不是失败态。
 */
public class AgentClarificationRequestedException extends RuntimeException {

    private final String displayMessage;

    public AgentClarificationRequestedException(AskClarificationRequest request) {
        super(render(request));
        this.displayMessage = render(request);
    }

    public String displayMessage() {
        return displayMessage;
    }

    private static String render(AskClarificationRequest request) {
        String question = textOrDefault(request == null ? null : request.question(),
                "Please provide the missing information required to continue.");
        String context = trimToNull(request == null ? null : request.context());
        List<String> options = request == null || request.options() == null
                ? List.of()
                : request.options().stream()
                .map(AgentClarificationRequestedException::trimToNull)
                .filter(value -> value != null && !value.isBlank())
                .toList();

        StringBuilder builder = new StringBuilder();
        if (context != null) {
            builder.append(context).append('\n');
        }
        builder.append(question);
        if (!options.isEmpty()) {
            builder.append("\n\n");
            for (int index = 0; index < options.size(); index++) {
                builder.append(index + 1)
                        .append(". ")
                        .append(options.get(index))
                        .append('\n');
            }
            builder.setLength(builder.length() - 1);
        }
        return builder.toString();
    }

    private static String textOrDefault(String value, String defaultValue) {
        String normalized = trimToNull(value);
        return normalized == null ? defaultValue : normalized;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
