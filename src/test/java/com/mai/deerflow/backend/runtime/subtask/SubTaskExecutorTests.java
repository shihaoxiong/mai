package com.mai.deerflow.backend.runtime.subtask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mai.deerflow.backend.runtime.agent.LeadAgentFactory;
import com.mai.deerflow.backend.runtime.event.ThreadEventService;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceProperties;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubTaskExecutorTests {

    @TempDir
    Path tempDir;

    @Test
    void shouldSubmitPersistAndReloadCompletedSubTask() {
        ThreadWorkspaceProperties workspaceProperties = new ThreadWorkspaceProperties();
        workspaceProperties.setBaseDir(tempDir.resolve("threads"));
        ThreadWorkspaceService threadWorkspaceService = new ThreadWorkspaceService(workspaceProperties);
        LeadAgentFactory leadAgentFactory = new LeadAgentFactory();
        ChatModel chatModel = prompt -> new ChatResponse(List.of(new Generation(
                new AssistantMessage("Processed: " + prompt.getInstructions().get(prompt.getInstructions().size() - 1).getText())
        )));
        ThreadEventService threadEventService = new ThreadEventService();
        ObjectMapper objectMapper = new ObjectMapper();

        SubTaskExecutor firstExecutor = new SubTaskExecutor(
                threadWorkspaceService,
                leadAgentFactory,
                chatModel,
                threadEventService,
                objectMapper,
                Runnable::run
        );

        SubTaskRecord submittedRecord = firstExecutor.submit(
                "subtask-thread",
                "run-1",
                "draft summary",
                "Prepare a short delegated summary for the parent agent."
        );

        assertThat(submittedRecord.status()).isEqualTo(SubTaskStatus.PENDING);
        assertThat(firstExecutor.list("subtask-thread"))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.status()).isEqualTo(SubTaskStatus.COMPLETED);
                    assertThat(record.result()).contains("Processed:");
                });

        SubTaskExecutor recoveredExecutor = new SubTaskExecutor(
                threadWorkspaceService,
                leadAgentFactory,
                chatModel,
                threadEventService,
                objectMapper,
                Runnable::run
        );

        assertThat(recoveredExecutor.find("subtask-thread", submittedRecord.taskId()))
                .hasValueSatisfying(record -> {
                    assertThat(record.status()).isEqualTo(SubTaskStatus.COMPLETED);
                    assertThat(record.title()).isEqualTo("draft summary");
                });
    }

    @Test
    void shouldExecuteSequentialAndParallelModes() {
        ThreadWorkspaceProperties workspaceProperties = new ThreadWorkspaceProperties();
        workspaceProperties.setBaseDir(tempDir.resolve("threads-multi"));
        ThreadWorkspaceService threadWorkspaceService = new ThreadWorkspaceService(workspaceProperties);
        LeadAgentFactory leadAgentFactory = new LeadAgentFactory();
        ChatModel chatModel = prompt -> {
            String joinedPrompt = prompt.getInstructions().stream()
                    .map(Message::getText)
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
            if (joinedPrompt.contains("Step instruction: extract risks")) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("risk_result"))));
            }
            if (joinedPrompt.contains("Step instruction: collect bullets")) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("collect_result"))));
            }
            if (joinedPrompt.contains("Step instruction: review findings")) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("review_result"))));
            }
            if (joinedPrompt.contains("Step instruction: research data")) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("research_result"))));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("Processed: " + joinedPrompt))));
        };
        ThreadEventService threadEventService = new ThreadEventService();
        ObjectMapper objectMapper = new ObjectMapper();
        SubTaskExecutor executor = new SubTaskExecutor(
                threadWorkspaceService,
                leadAgentFactory,
                chatModel,
                threadEventService,
                objectMapper,
                Runnable::run
        );

        SubTaskRecord sequentialRecord = executor.submit(
                "subtask-multi-thread",
                "run-seq",
                "sequential flow",
                "Coordinate a sequential review.",
                SubTaskOrchestrationMode.SEQUENTIAL,
                List.of(
                        new SubTaskStep("research", "research data"),
                        new SubTaskStep("review", "review findings")
                )
        );
        SubTaskRecord parallelRecord = executor.submit(
                "subtask-multi-thread",
                "run-par",
                "parallel flow",
                "Coordinate a parallel review.",
                SubTaskOrchestrationMode.PARALLEL,
                List.of(
                        new SubTaskStep("collect", "collect bullets"),
                        new SubTaskStep("risk", "extract risks")
                )
        );

        assertThat(executor.find("subtask-multi-thread", sequentialRecord.taskId()))
                .hasValueSatisfying(record -> {
                    assertThat(record.status()).isEqualTo(SubTaskStatus.COMPLETED);
                    assertThat(record.result()).contains("review_result");
                });
        assertThat(executor.find("subtask-multi-thread", parallelRecord.taskId()))
                .hasValueSatisfying(record -> {
                    assertThat(record.status()).isEqualTo(SubTaskStatus.COMPLETED);
                    assertThat(record.result()).contains("collect_result").contains("risk_result");
                });
    }
}
