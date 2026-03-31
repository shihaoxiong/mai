package com.mai.deerflow.backend.runtime.agent;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mai.deerflow.backend.runtime.checkpoint.RuntimeCheckpointProperties;
import com.mai.deerflow.backend.runtime.checkpoint.RuntimeCheckpointService;
import com.mai.deerflow.backend.runtime.contract.TodoItem;
import com.mai.deerflow.backend.runtime.contract.TodoStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeAgentEnhancementServiceTests {

    @TempDir
    Path tempDir;

    private final RuntimeAgentEnhancementService runtimeAgentEnhancementService =
            new RuntimeAgentEnhancementService(new ObjectMapper());
    private final LeadAgentFactory leadAgentFactory = new LeadAgentFactory();

    @Test
    void todoListInterceptorShouldEnableWriteTodosToolFlow() throws Exception {
        ChatModel planningModel = new PlanningChatModel();

        var agent = leadAgentFactory.create(LeadAgentDefinition.builder(planningModel)
                .name("planning-agent")
                .instruction("Plan the work.")
                .interceptors(runtimeAgentEnhancementService.defaultInterceptors())
                .saver(new MemorySaver())
                .build());

        RunnableConfig config = RunnableConfig.builder().threadId("planning-thread").build();
        AssistantMessage assistantMessage = agent.call("plan this work", config);

        List<TodoItem> todos = runtimeAgentEnhancementService.extractTodos(
                agent.getCompiledGraph().getState(config).state().data()
        );

        assertThat(assistantMessage.getText()).isEqualTo("planning-finished");
        assertThat(todos).extracting(TodoItem::title)
                .containsExactly("Read uploaded brief", "Draft summary");
    }

    @Test
    void summarizationHookShouldInsertSummaryMessageWhenThresholdIsExceeded() throws Exception {
        ChatModel summaryModel = new SummaryAwareChatModel();

        var agent = leadAgentFactory.create(LeadAgentDefinition.builder(summaryModel)
                .name("summary-agent")
                .instruction("Summarize older messages when needed.")
                .hooks(List.of(runtimeAgentEnhancementService.summarizationHook(
                        summaryModel,
                        1,
                        1,
                        "Conversation summary:\n"
                )))
                .saver(new MemorySaver())
                .build());

        RunnableConfig config = RunnableConfig.builder().threadId("summary-thread").build();
        agent.call("first long message", config);
        agent.call("second long message", config);

        List<String> messageTexts = runtimeAgentEnhancementService.extractMessageTexts(
                agent.getCompiledGraph().getState(config).state().data()
        );

        assertThat(messageTexts).anyMatch(text -> text.contains("Conversation summary:"));
    }

    @Test
    void toolCallSafetyInterceptorShouldTruncateExcessTaskCalls() throws Exception {
        AtomicInteger taskInvocationCount = new AtomicInteger();
        var taskTool = org.springframework.ai.tool.function.FunctionToolCallback
                .builder("task", (TaskRequest request) -> {
                    taskInvocationCount.incrementAndGet();
                    return "task-result:" + request.title();
                })
                .description("Synthetic task tool for testing.")
                .inputType(TaskRequest.class)
                .build();

        var agent = leadAgentFactory.create(LeadAgentDefinition.builder(new ManyTaskCallsChatModel())
                .name("task-limit-agent")
                .instruction("Limit task fan-out.")
                .tools(List.of(taskTool))
                .interceptors(runtimeAgentEnhancementService.defaultInterceptors())
                .saver(new MemorySaver())
                .build());

        AssistantMessage assistantMessage = agent.call("launch many tasks");

        assertThat(taskInvocationCount.get()).isEqualTo(3);
        assertThat(assistantMessage.getText()).isEqualTo("taskResponses=3");
    }

    @Test
    void toolCallSafetyInterceptorShouldForceStopRepeatedToolLoops() throws Exception {
        AtomicInteger echoInvocationCount = new AtomicInteger();
        var echoTool = org.springframework.ai.tool.function.FunctionToolCallback
                .builder("echo", (EchoRequest request) -> {
                    echoInvocationCount.incrementAndGet();
                    return "echo:" + request.value();
                })
                .description("Synthetic loop tool for testing.")
                .inputType(EchoRequest.class)
                .build();

        var agent = leadAgentFactory.create(LeadAgentDefinition.builder(new RepeatingToolLoopChatModel())
                .name("loop-guard-agent")
                .instruction("Stop repeated tool loops.")
                .tools(List.of(echoTool))
                .interceptors(runtimeAgentEnhancementService.defaultInterceptors())
                .saver(new MemorySaver())
                .build());

        AssistantMessage assistantMessage = agent.call("repeat the same tool call");

        assertThat(echoInvocationCount.get()).isEqualTo(4);
        assertThat(assistantMessage.getText()).contains(RuntimeToolCallSafetyInterceptor.FORCED_STOP_MESSAGE);
    }

    @Test
    void todoReminderInterceptorShouldInjectReminderWhenTodosExistButWriteTodosIsOutOfContext() throws Exception {
        RuntimeCheckpointProperties checkpointProperties = new RuntimeCheckpointProperties();
        checkpointProperties.setBaseDir(tempDir.resolve("checkpoints"));
        RuntimeCheckpointService runtimeCheckpointService = new RuntimeCheckpointService(checkpointProperties);
        runtimeCheckpointService.leadAgentSaver().put(
                RunnableConfig.builder().threadId("todo-reminder-thread").build(),
                Checkpoint.builder()
                        .id("todo-reminder-checkpoint")
                        .state(Map.of(
                                "todos",
                                List.of(
                                        new TodoItem("todo-1", "Read uploaded brief", TodoStatus.IN_PROGRESS),
                                        new TodoItem("todo-2", "Draft summary", TodoStatus.PENDING)
                                )
                        ))
                        .nodeId("__START__")
                        .nextNodeId("_AGENT_MODEL_")
                        .build()
        );

        var agent = leadAgentFactory.create(LeadAgentDefinition.builder(new TodoReminderAwareChatModel())
                .name("todo-reminder-agent")
                .instruction("Remember active todos.")
                .interceptors(List.of(new RuntimeTodoReminderInterceptor(
                        "todo-reminder-thread",
                        runtimeCheckpointService,
                        runtimeAgentEnhancementService
                )))
                .saver(new MemorySaver())
                .build());

        AssistantMessage assistantMessage = agent.call(
                "continue implementation",
                RunnableConfig.builder().threadId("todo-reminder-thread").build()
        );

        assertThat(assistantMessage.getText())
                .contains("todoReminder=true")
                .contains("Read uploaded brief")
                .contains("Draft summary");
    }

    @Test
    void viewImageInterceptorShouldInjectImageMessageAfterToolCompletion() throws Exception {
        String imagePayload = """
                {"path":"/uploads/sample.png","mimeType":"image/png","base64":"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jC3sAAAAASUVORK5CYII="}
                """;

        var viewImageTool = FunctionToolCallback
                .builder("view_image", (ViewImageRequest request) -> imagePayload)
                .description("Synthetic view_image tool for testing.")
                .inputType(ViewImageRequest.class)
                .build();

        var agent = leadAgentFactory.create(LeadAgentDefinition.builder(new ViewImageAwareChatModel())
                .name("view-image-agent")
                .instruction("Inspect viewed images.")
                .tools(List.of(viewImageTool))
                .interceptors(runtimeAgentEnhancementService.supplementalInterceptors())
                .saver(new MemorySaver())
                .build());

        AssistantMessage assistantMessage = agent.call("look at the uploaded image");

        assertThat(assistantMessage.getText())
                .contains("imageInjected=true")
                .contains("mediaCount=1")
                .contains("/uploads/sample.png");
    }

    @Test
    void toolErrorHandlingInterceptorShouldConvertToolFailureIntoToolResponse() throws Exception {
        var brokenTool = FunctionToolCallback
                .builder("broken_tool", (BrokenToolRequest request) -> {
                    throw new IllegalStateException("simulated tool failure for " + request.input());
                })
                .description("Synthetic failing tool for testing.")
                .inputType(BrokenToolRequest.class)
                .build();

        var agent = leadAgentFactory.create(LeadAgentDefinition.builder(new ToolFailureAwareChatModel())
                .name("tool-error-agent")
                .instruction("Continue after tool failure.")
                .tools(List.of(brokenTool))
                .interceptors(runtimeAgentEnhancementService.defaultInterceptors())
                .toolExecutionExceptionProcessor(new RuntimeToolExecutionExceptionProcessor())
                .saver(new MemorySaver())
                .build());

        AssistantMessage assistantMessage = agent.call("use the broken tool");

        assertThat(assistantMessage.getText())
                .contains("toolErrorHandled=true")
                .contains("broken_tool")
                .contains("simulated tool failure");
    }

    record TaskRequest(String title) {
    }

    record EchoRequest(String value) {
    }

    record ViewImageRequest(String path) {
    }

    record BrokenToolRequest(String input) {
    }

    private static final class PlanningChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            boolean hasToolResponse = messages.stream().anyMatch(ToolResponseMessage.class::isInstance);

            if (!hasToolResponse) {
                AssistantMessage toolCall = AssistantMessage.builder()
                        .content("Planning work")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "todo-call-1",
                                "function",
                                "write_todos",
                                """
                                        {
                                          "todos": [
                                            {"content": "Read uploaded brief", "status": "IN_PROGRESS"},
                                            {"content": "Draft summary", "status": "PENDING"}
                                          ]
                                        }
                                        """
                        )))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCall)));
            }

            return new ChatResponse(List.of(new Generation(new AssistantMessage("planning-finished"))));
        }
    }

    private static final class SummaryAwareChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            boolean hasSummaryMessage = messages.stream()
                    .filter(SystemMessage.class::isInstance)
                    .map(SystemMessage.class::cast)
                    .map(SystemMessage::getText)
                    .anyMatch(text -> text.contains("Conversation summary:"));

            String lastUserMessage = messages.stream()
                    .filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast)
                    .reduce((previous, current) -> current)
                    .map(UserMessage::getText)
                    .orElse("");

            String response = hasSummaryMessage
                    ? "summary-observed:" + lastUserMessage
                    : "plain-response:" + lastUserMessage;

            return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
        }
    }

    private static final class ManyTaskCallsChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            List<ToolResponseMessage> toolResponses = messages.stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .toList();

            if (toolResponses.isEmpty()) {
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .content("Launch four tasks")
                        .toolCalls(List.of(
                                new AssistantMessage.ToolCall("task-1", "function", "task", "{\"title\":\"one\"}"),
                                new AssistantMessage.ToolCall("task-2", "function", "task", "{\"title\":\"two\"}"),
                                new AssistantMessage.ToolCall("task-3", "function", "task", "{\"title\":\"three\"}"),
                                new AssistantMessage.ToolCall("task-4", "function", "task", "{\"title\":\"four\"}")
                        ))
                        .build())));
            }

            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "taskResponses=" + toolResponses.get(0).getResponses().size()
            ))));
        }
    }

    private static final class RepeatingToolLoopChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            List<AssistantMessage> assistantMessages = messages.stream()
                    .filter(AssistantMessage.class::isInstance)
                    .map(AssistantMessage.class::cast)
                    .toList();
            AssistantMessage lastAssistantMessage = assistantMessages.isEmpty()
                    ? null
                    : assistantMessages.get(assistantMessages.size() - 1);
            if (lastAssistantMessage != null
                    && !lastAssistantMessage.hasToolCalls()
                    && lastAssistantMessage.getText() != null
                    && lastAssistantMessage.getText().contains(RuntimeToolCallSafetyInterceptor.FORCED_STOP_MESSAGE)) {
                return new ChatResponse(List.of(new Generation(lastAssistantMessage)));
            }

            return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                    .content("Repeat same tool call")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "echo-repeat",
                            "function",
                            "echo",
                            "{\"value\":\"same\"}"
                    )))
                    .build())));
        }
    }

    private static final class TodoReminderAwareChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            String reminder = messages.stream()
                    .filter(SystemMessage.class::isInstance)
                    .map(SystemMessage.class::cast)
                    .map(SystemMessage::getText)
                    .filter(text -> text.contains("<todo_reminder>"))
                    .findFirst()
                    .orElse("");

            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "todoReminder=%s; content=%s".formatted(!reminder.isBlank(), reminder)
            ))));
        }
    }

    private static final class ViewImageAwareChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            List<ToolResponseMessage> toolResponses = messages.stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .toList();

            if (toolResponses.isEmpty()) {
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .content("Load the image first")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "view-image-1",
                                "function",
                                "view_image",
                                "{\"path\":\"/uploads/sample.png\"}"
                        )))
                        .build())));
            }

            UserMessage injectedImageMessage = messages.stream()
                    .filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast)
                    .filter(message -> message.getText() != null && message.getText().contains("Here are the images you've viewed:"))
                    .findFirst()
                    .orElseThrow();

            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "imageInjected=true; mediaCount=%d; content=%s".formatted(
                            injectedImageMessage.getMedia().size(),
                            injectedImageMessage.getText()
                    )
            ))));
        }
    }

    private static final class ToolFailureAwareChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            List<ToolResponseMessage> toolResponses = messages.stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .toList();

            if (toolResponses.isEmpty()) {
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .content("Call the failing tool first")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "broken-call-1",
                                "function",
                                "broken_tool",
                                "{\"input\":\"alpha\"}"
                        )))
                        .build())));
            }

            ToolResponseMessage.ToolResponse toolResponse = toolResponses.get(0).getResponses().get(0);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "toolErrorHandled=%s; payload=%s".formatted(
                            toolResponse.responseData().contains("Error: Tool 'broken_tool' failed"),
                            toolResponse.responseData()
                    )
            ))));
        }
    }
}
