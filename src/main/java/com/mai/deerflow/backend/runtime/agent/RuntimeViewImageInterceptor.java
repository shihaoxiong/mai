package com.mai.deerflow.backend.runtime.agent;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在 `view_image` 工具完成后，把图片内容以临时多模态消息注入到下一次模型调用。
 */
public class RuntimeViewImageInterceptor extends ModelInterceptor {

    private final ObjectMapper objectMapper;

    public RuntimeViewImageInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "runtime-view-image-interceptor";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        AssistantMessage lastAssistantMessage = lastAssistantMessage(request.getMessages());
        if (lastAssistantMessage == null || !containsViewImageTool(lastAssistantMessage)) {
            return handler.call(request);
        }

        int assistantIndex = request.getMessages().lastIndexOf(lastAssistantMessage);
        if (assistantIndex < 0) {
            return handler.call(request);
        }

        List<Message> trailingMessages = request.getMessages().subList(assistantIndex + 1, request.getMessages().size());
        if (!allToolCallsCompleted(lastAssistantMessage, trailingMessages) || imageMessageAlreadyInjected(trailingMessages)) {
            return handler.call(request);
        }

        List<ViewedImageData> viewedImages = viewedImages(trailingMessages);
        if (viewedImages.isEmpty()) {
            return handler.call(request);
        }

        List<Message> messages = new ArrayList<>(request.getMessages());
        messages.add(buildImageDetailsMessage(viewedImages));
        return handler.call(ModelRequest.builder(request)
                .messages(messages)
                .build());
    }

    private AssistantMessage lastAssistantMessage(List<Message> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof AssistantMessage assistantMessage) {
                return assistantMessage;
            }
        }
        return null;
    }

    private boolean containsViewImageTool(AssistantMessage assistantMessage) {
        return assistantMessage.hasToolCalls()
                && assistantMessage.getToolCalls().stream().anyMatch(toolCall -> "view_image".equals(toolCall.name()));
    }

    private boolean allToolCallsCompleted(AssistantMessage assistantMessage, List<Message> trailingMessages) {
        Map<String, String> completedById = new LinkedHashMap<>();
        for (Message trailingMessage : trailingMessages) {
            if (trailingMessage instanceof ToolResponseMessage toolResponseMessage) {
                for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                    completedById.put(response.id(), response.name());
                }
            }
        }

        return assistantMessage.getToolCalls().stream()
                .allMatch(toolCall -> completedById.containsKey(toolCall.id()));
    }

    private boolean imageMessageAlreadyInjected(List<Message> trailingMessages) {
        return trailingMessages.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .map(UserMessage::getText)
                .anyMatch(text -> text != null && text.contains("Here are the images you've viewed:"));
    }

    private List<ViewedImageData> viewedImages(List<Message> trailingMessages) {
        List<ViewedImageData> viewedImages = new ArrayList<>();
        for (Message trailingMessage : trailingMessages) {
            if (!(trailingMessage instanceof ToolResponseMessage toolResponseMessage)) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                if (!"view_image".equals(response.name())) {
                    continue;
                }
                ViewedImageData viewedImageData = parseViewedImage(response.responseData());
                if (viewedImageData != null) {
                    viewedImages.add(viewedImageData);
                }
            }
        }
        return List.copyOf(viewedImages);
    }

    private ViewedImageData parseViewedImage(String responseData) {
        if (responseData == null || responseData.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(responseData, ViewedImageData.class);
        }
        catch (Exception exception) {
            return null;
        }
    }

    private UserMessage buildImageDetailsMessage(List<ViewedImageData> viewedImages) {
        List<Media> media = new ArrayList<>();
        StringBuilder text = new StringBuilder("Here are the images you've viewed:");
        for (ViewedImageData viewedImageData : viewedImages) {
            text.append("\n- ")
                    .append(viewedImageData.path())
                    .append(" (")
                    .append(viewedImageData.mimeType())
                    .append(")");

            byte[] bytes = Base64.getDecoder().decode(viewedImageData.base64());
            MimeType mimeType = MimeTypeUtils.parseMimeType(viewedImageData.mimeType());
            media.add(Media.builder()
                    .mimeType(mimeType)
                    .name(viewedImageData.path())
                    .data(new ByteArrayResource(bytes))
                    .build());
        }

        return UserMessage.builder()
                .text(text.toString())
                .media(media)
                .build();
    }
}
