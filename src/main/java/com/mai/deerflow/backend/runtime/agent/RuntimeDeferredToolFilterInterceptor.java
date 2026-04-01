package com.mai.deerflow.backend.runtime.agent;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 默认隐藏 deferred tools，只有在 `tool_search` 返回对应 schema 后才对模型暴露。
 */
public class RuntimeDeferredToolFilterInterceptor extends ModelInterceptor {

    private final Set<String> deferredToolNames;
    private final ObjectMapper objectMapper;

    public RuntimeDeferredToolFilterInterceptor(List<String> deferredToolNames, ObjectMapper objectMapper) {
        this.deferredToolNames = new LinkedHashSet<>(deferredToolNames);
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "runtime-deferred-tool-filter-interceptor";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        List<String> visibleTools = request.getTools();
        if (visibleTools == null || visibleTools.isEmpty() || deferredToolNames.isEmpty()) {
            return handler.call(request);
        }

        Set<String> selectedNames = selectedDeferredToolNames(request.getMessages());
        List<String> filteredTools = visibleTools.stream()
                .filter(name -> !deferredToolNames.contains(name) || selectedNames.contains(name))
                .toList();

        if (filteredTools.equals(visibleTools)) {
            return handler.call(request);
        }

        Map<String, String> filteredDescriptions = request.getToolDescriptions() == null
                ? null
                : request.getToolDescriptions().entrySet().stream()
                        .filter(entry -> filteredTools.contains(entry.getKey()))
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                Map.Entry::getValue,
                                (left, right) -> left,
                                java.util.LinkedHashMap::new
                        ));

        return handler.call(ModelRequest.builder(request)
                .systemMessage(request.getSystemMessage())
                .tools(filteredTools)
                .toolDescriptions(filteredDescriptions)
                .build());
    }

    private Set<String> selectedDeferredToolNames(List<Message> messages) {
        Set<String> selectedNames = new LinkedHashSet<>();
        for (Message message : messages) {
            if (!(message instanceof ToolResponseMessage toolResponseMessage)) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                if (!"tool_search".equals(response.name())) {
                    continue;
                }
                selectedNames.addAll(parseSelectedNames(response.responseData()));
            }
        }
        return selectedNames;
    }

    private List<String> parseSelectedNames(String responseData) {
        if (responseData == null || responseData.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(responseData);
            if (!root.isArray()) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            for (JsonNode item : root) {
                JsonNode name = item.get("name");
                if (name != null && name.isTextual() && !name.asText().isBlank()) {
                    names.add(name.asText());
                }
            }
            return List.copyOf(names);
        }
        catch (Exception exception) {
            return List.of();
        }
    }
}
