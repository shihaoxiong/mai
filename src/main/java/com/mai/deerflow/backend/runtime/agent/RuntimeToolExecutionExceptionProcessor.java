package com.mai.deerflow.backend.runtime.agent;

import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;

/**
 * 统一格式化运行时工具异常，把底层异常转换成对模型更友好的工具结果文本。
 */
public class RuntimeToolExecutionExceptionProcessor implements ToolExecutionExceptionProcessor {

    private static final int MAX_ERROR_DETAIL_LENGTH = 500;

    @Override
    public String process(ToolExecutionException exception) {
        ToolDefinition toolDefinition = exception == null ? null : exception.getToolDefinition();
        String toolName = toolDefinition == null || !hasText(toolDefinition.name())
                ? "unknown_tool"
                : toolDefinition.name();
        Throwable cause = exception != null && exception.getCause() != null ? exception.getCause() : exception;
        String exceptionName = cause == null ? "RuntimeException" : cause.getClass().getSimpleName();
        String detail = cause == null || !hasText(cause.getMessage())
                ? exceptionName
                : abbreviate(cause.getMessage().trim());

        return "Error: Tool '%s' failed with %s: %s. Continue with available context, or choose an alternative tool."
                .formatted(toolName, exceptionName, detail);
    }

    private String abbreviate(String detail) {
        if (detail.length() <= MAX_ERROR_DETAIL_LENGTH) {
            return detail;
        }
        return detail.substring(0, MAX_ERROR_DETAIL_LENGTH - 3) + "...";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
