package com.mai.deerflow.backend.runtime.subtask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mai.deerflow.backend.runtime.agent.LeadAgentFactory;
import com.mai.deerflow.backend.runtime.event.ThreadEventService;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceProperties;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
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
}
