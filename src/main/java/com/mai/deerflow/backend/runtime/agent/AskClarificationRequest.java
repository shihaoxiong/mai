package com.mai.deerflow.backend.runtime.agent;

import java.util.List;

/**
 * lead agent 主动向用户请求澄清时使用的工具输入。
 */
public record AskClarificationRequest(
        String question,
        String context,
        List<String> options,
        String clarificationType
) {
}
