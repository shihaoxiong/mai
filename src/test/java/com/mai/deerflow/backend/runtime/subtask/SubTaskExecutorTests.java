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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void shouldTimeoutAndRetrySubTask() {
        ThreadWorkspaceProperties workspaceProperties = new ThreadWorkspaceProperties();
        workspaceProperties.setBaseDir(tempDir.resolve("threads-timeout"));
        ThreadWorkspaceService threadWorkspaceService = new ThreadWorkspaceService(workspaceProperties);
        LeadAgentFactory leadAgentFactory = new LeadAgentFactory();
        AtomicInteger delegatedCalls = new AtomicInteger();
        ChatModel chatModel = prompt -> {
            int currentCall = delegatedCalls.incrementAndGet();
            if (currentCall == 1) {
                try {
                    Thread.sleep(200);
                }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("interrupted_timeout"))));
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage("late_result"))));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("retry_success"))));
        };
        ThreadEventService threadEventService = new ThreadEventService();
        ObjectMapper objectMapper = new ObjectMapper();
        ExecutorService executionExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "subtask-timeout-test");
            thread.setDaemon(true);
            return thread;
        });
        ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "subtask-timeout-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        SubTaskExecutor executor = new SubTaskExecutor(
                threadWorkspaceService,
                leadAgentFactory,
                chatModel,
                threadEventService,
                objectMapper,
                executionExecutor,
                timeoutScheduler
        );

        try {
            SubTaskRecord timedOutRecord = executor.submit(
                    "subtask-timeout-thread",
                    "run-timeout",
                    "timeout flow",
                    "This task should time out first.",
                    SubTaskOrchestrationMode.SINGLE,
                    List.of(),
                    50L,
                    0
            );

            SubTaskRecord observedTimedOutRecord = awaitTerminalRecord(
                    executor,
                    "subtask-timeout-thread",
                    timedOutRecord.taskId()
            );
            assertThat(observedTimedOutRecord.status()).isEqualTo(SubTaskStatus.TIMED_OUT);
            assertThat(observedTimedOutRecord.errorMessage()).contains("timed out");

            SubTaskRecord retriedRecord = executor.retry("subtask-timeout-thread", timedOutRecord.taskId(), 500L);
            SubTaskRecord observedRetriedRecord = awaitTerminalRecord(
                    executor,
                    "subtask-timeout-thread",
                    retriedRecord.taskId()
            );
            assertThat(observedRetriedRecord.status()).isEqualTo(SubTaskStatus.COMPLETED);
            assertThat(observedRetriedRecord.retryCount()).isEqualTo(1);
            assertThat(observedRetriedRecord.result()).contains("retry_success");
        }
        finally {
            executionExecutor.shutdownNow();
            timeoutScheduler.shutdownNow();
        }
    }

    @Test
    void shouldCancelRunningSubTask() throws Exception {
        ThreadWorkspaceProperties workspaceProperties = new ThreadWorkspaceProperties();
        workspaceProperties.setBaseDir(tempDir.resolve("threads-cancel"));
        ThreadWorkspaceService threadWorkspaceService = new ThreadWorkspaceService(workspaceProperties);
        LeadAgentFactory leadAgentFactory = new LeadAgentFactory();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ChatModel chatModel = prompt -> {
            started.countDown();
            try {
                release.await();
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return new ChatResponse(List.of(new Generation(new AssistantMessage("cancelled"))));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("should_not_finish"))));
        };
        ThreadEventService threadEventService = new ThreadEventService();
        ObjectMapper objectMapper = new ObjectMapper();
        ExecutorService executionExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "subtask-cancel-test");
            thread.setDaemon(true);
            return thread;
        });
        ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "subtask-cancel-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        SubTaskExecutor executor = new SubTaskExecutor(
                threadWorkspaceService,
                leadAgentFactory,
                chatModel,
                threadEventService,
                objectMapper,
                executionExecutor,
                timeoutScheduler
        );

        try {
            SubTaskRecord submittedRecord = executor.submit(
                    "subtask-cancel-thread",
                    "run-cancel",
                    "cancel flow",
                    "This task will be cancelled.",
                    SubTaskOrchestrationMode.SINGLE,
                    List.of(),
                    5_000L,
                    0
            );

            started.await();
            SubTaskRecord cancelledRecord = executor.cancel("subtask-cancel-thread", submittedRecord.taskId());

            assertThat(cancelledRecord.status()).isEqualTo(SubTaskStatus.CANCELLED);
            assertThat(executor.find("subtask-cancel-thread", submittedRecord.taskId()))
                    .hasValueSatisfying(record -> assertThat(record.status()).isEqualTo(SubTaskStatus.CANCELLED));
        }
        finally {
            release.countDown();
            executionExecutor.shutdownNow();
            timeoutScheduler.shutdownNow();
        }
    }

    private SubTaskRecord awaitTerminalRecord(SubTaskExecutor executor, String threadId, String taskId) {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            SubTaskRecord record = executor.find(threadId, taskId).orElseThrow();
            if (record.status() == SubTaskStatus.TIMED_OUT
                    || record.status() == SubTaskStatus.CANCELLED
                    || record.status() == SubTaskStatus.COMPLETED
                    || record.status() == SubTaskStatus.FAILED) {
                return record;
            }
            try {
                Thread.sleep(20L);
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return executor.find(threadId, taskId).orElseThrow();
    }
}
