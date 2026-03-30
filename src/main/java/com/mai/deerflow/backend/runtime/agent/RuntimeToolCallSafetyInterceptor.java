package com.mai.deerflow.backend.runtime.agent;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 为 runtime lead agent 提供最小必要的 tool-call 安全护栏。
 *
 * 当前包含两类保护：
 * 1. 限制单轮模型响应里 `task` 工具调用数量
 * 2. 当检测到同一组 tool calls 在历史中重复过多次时，强制去掉本轮 tool calls，要求模型停止循环
 */
public class RuntimeToolCallSafetyInterceptor extends ModelInterceptor {

    static final String FORCED_STOP_MESSAGE = "[FORCED STOP] Repeated tool calls exceeded the runtime safety limit. Produce a final answer with the results collected so far.";

    private static final int DEFAULT_MAX_CONCURRENT_TASK_CALLS = 3;
    private static final int DEFAULT_TOOL_LOOP_HARD_LIMIT = 5;

    private final int maxConcurrentTaskCalls;
    private final int toolLoopHardLimit;

    public RuntimeToolCallSafetyInterceptor() {
        this(DEFAULT_MAX_CONCURRENT_TASK_CALLS, DEFAULT_TOOL_LOOP_HARD_LIMIT);
    }

    public RuntimeToolCallSafetyInterceptor(int maxConcurrentTaskCalls, int toolLoopHardLimit) {
        this.maxConcurrentTaskCalls = Math.max(1, maxConcurrentTaskCalls);
        this.toolLoopHardLimit = Math.max(2, toolLoopHardLimit);
    }

    @Override
    public String getName() {
        return "runtime-tool-call-safety-interceptor";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        ModelResponse response = handler.call(request);
        if (!(response.getMessage() instanceof AssistantMessage assistantMessage) || !assistantMessage.hasToolCalls()) {
            return response;
        }

        AssistantMessage adjustedMessage = assistantMessage;
        adjustedMessage = truncateTaskCallsIfNeeded(adjustedMessage);
        adjustedMessage = forceStopRepeatedLoopIfNeeded(request.getMessages(), adjustedMessage);

        if (adjustedMessage == assistantMessage) {
            return response;
        }
        return ModelResponse.of(adjustedMessage, response.getChatResponse());
    }

    private AssistantMessage truncateTaskCallsIfNeeded(AssistantMessage assistantMessage) {
        List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
        List<AssistantMessage.ToolCall> retained = new ArrayList<>(toolCalls.size());
        int taskCount = 0;
        boolean changed = false;

        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            if ("task".equals(toolCall.name())) {
                taskCount++;
                if (taskCount > maxConcurrentTaskCalls) {
                    changed = true;
                    continue;
                }
            }
            retained.add(toolCall);
        }

        return changed ? copyAssistantMessage(assistantMessage, retained, assistantMessage.getText()) : assistantMessage;
    }

    private AssistantMessage forceStopRepeatedLoopIfNeeded(List<Message> history, AssistantMessage assistantMessage) {
        String currentHash = toolCallHash(assistantMessage.getToolCalls());
        long repeatedCount = history.stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .filter(AssistantMessage::hasToolCalls)
                .map(AssistantMessage::getToolCalls)
                .map(this::toolCallHash)
                .filter(currentHash::equals)
                .count() + 1;

        if (repeatedCount < toolLoopHardLimit) {
            return assistantMessage;
        }

        String content = assistantMessage.getText() == null || assistantMessage.getText().isBlank()
                ? FORCED_STOP_MESSAGE
                : assistantMessage.getText() + "\n\n" + FORCED_STOP_MESSAGE;
        return copyAssistantMessage(assistantMessage, List.of(), content);
    }

    private AssistantMessage copyAssistantMessage(AssistantMessage assistantMessage,
                                                  List<AssistantMessage.ToolCall> toolCalls,
                                                  String content) {
        return AssistantMessage.builder()
                .content(content)
                .properties(assistantMessage.getMetadata())
                .toolCalls(toolCalls)
                .media(assistantMessage.getMedia())
                .build();
    }

    private String toolCallHash(List<AssistantMessage.ToolCall> toolCalls) {
        String normalized = toolCalls.stream()
                .map(toolCall -> toolCall.name() + "::" + (toolCall.arguments() == null ? "" : toolCall.arguments()))
                .sorted(Comparator.naturalOrder())
                .reduce((left, right) -> left + "||" + right)
                .orElse("");
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 algorithm is unavailable", exception);
        }
    }
}
