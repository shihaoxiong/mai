package com.mai.deerflow.backend.runtime.agent;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 把普通工具异常转换成 error `ToolCallResponse`，避免一次工具失败直接打断整条 lead agent 主链路。
 *
 * 注意：
 * - `ask_clarification` 触发的受控中断必须继续向上冒泡，不能被当成普通错误吞掉。
 * - 这里只兜底“工具执行异常”，不改变工具本身的成功返回语义。
 */
public class RuntimeToolErrorHandlingInterceptor extends ToolInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(RuntimeToolErrorHandlingInterceptor.class);
    private static final int MAX_ERROR_DETAIL_LENGTH = 500;

    @Override
    public String getName() {
        return "runtime-tool-error-handling-interceptor";
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        try {
            return handler.call(request);
        }
        catch (AgentClarificationRequestedException clarificationRequestedException) {
            throw clarificationRequestedException;
        }
        catch (Exception exception) {
            logger.warn(
                    "Tool execution failed: name={}, id={}",
                    request.getToolName(),
                    request.getToolCallId(),
                    exception
            );
            return ToolCallResponse.builder()
                    .toolName(request.getToolName())
                    .toolCallId(request.getToolCallId())
                    .content(errorMessage(request, exception))
                    .metadata(Map.of(
                            "exceptionType", exception.getClass().getName()
                    ))
                    .build();
        }
    }

    private String errorMessage(ToolCallRequest request, Exception exception) {
        String toolName = hasText(request.getToolName()) ? request.getToolName() : "unknown_tool";
        String detail = abbreviate(hasText(exception.getMessage()) ? exception.getMessage().trim() : exception.getClass().getSimpleName());
        return "Error: Tool '%s' failed with %s: %s. Continue with available context, or choose an alternative tool."
                .formatted(toolName, exception.getClass().getSimpleName(), detail);
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
