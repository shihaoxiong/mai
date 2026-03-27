package com.mai.deerflow.backend.runtime.e2e;

import com.mai.deerflow.backend.runtime.contract.RunEventType;
import com.mai.deerflow.backend.runtime.event.ThreadEventService;
import com.mai.deerflow.backend.runtime.memory.MemoryFact;
import com.mai.deerflow.backend.runtime.memory.MemoryInjectionService;
import com.mai.deerflow.backend.runtime.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdvancedRuntimeE2ETests {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ThreadEventService threadEventService;

    @Autowired
    private MemoryStore memoryStore;

    @SpyBean
    private MemoryInjectionService memoryInjectionService;

    private final List<String> createdThreadIds = new ArrayList<>();
    private final List<String> createdUserIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        createdThreadIds.forEach(threadId -> webTestClient.delete()
                .uri("/api/threads/" + threadId)
                .exchange()
                .expectStatus().value(status -> assertThat(status).isIn(204, 404)));

        createdUserIds.forEach(userId -> memoryStore.list(userId)
                .forEach(memoryFact -> memoryStore.delete(userId, memoryFact.memoryId())));
    }

    @Test
    void shouldReuseLongTermMemoryAcrossThreadsForSameUser() {
        String userId = "e2e-user-" + UUID.randomUUID();
        String firstThreadId = "memory-e2e-a-" + UUID.randomUUID();
        String secondThreadId = "memory-e2e-b-" + UUID.randomUUID();
        createdUserIds.add(userId);
        createdThreadIds.add(firstThreadId);
        createdThreadIds.add(secondThreadId);

        createThread(firstThreadId);
        webTestClient.post()
                .uri("/api/threads/" + firstThreadId + "/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "message": "I use Java 17 locally. Please keep comments in Chinese.",
                          "userId": "%s"
                        }
                        """.formatted(userId))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.runStatus").isEqualTo("COMPLETED");

        awaitMemoryFacts(userId, 2);
        clearInvocations(memoryInjectionService);

        createThread(secondThreadId);
        webTestClient.post()
                .uri("/api/threads/" + secondThreadId + "/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "message": "continue my backend task",
                          "userId": "%s"
                        }
                        """.formatted(userId))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.runStatus").isEqualTo("COMPLETED")
                .jsonPath("$.title").isEqualTo("continue my backend task");

        verify(memoryInjectionService, atLeastOnce()).inject(eq(userId), eq("continue my backend task"));
        assertThat(memoryStore.list(userId)).isNotEmpty();
    }

    @Test
    void shouldCompleteSubTaskScenarioEndToEnd() {
        String threadId = "subtask-e2e-" + UUID.randomUUID();
        createdThreadIds.add(threadId);
        createThread(threadId);

        webTestClient.post()
                .uri("/api/threads/" + threadId + "/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "message": "please delegate this work in parallel"
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.runStatus").isEqualTo("COMPLETED")
                .jsonPath("$.suggestions[0]").isEqualTo("review the delegated subtask results");

        StepVerifier.create(threadEventService.stream(threadId)
                        .take(4)
                        .map(event -> event.data().eventType()))
                .expectNext(
                        RunEventType.RUN_STARTED,
                        RunEventType.SUBTASK_STARTED,
                        RunEventType.SUBTASK_UPDATED,
                        RunEventType.RUN_COMPLETED
                )
                .verifyComplete();
    }

    @Test
    void shouldHandleClarificationApprovalRecoveryEndToEnd() {
        String threadId = "clarification-e2e-" + UUID.randomUUID();
        createdThreadIds.add(threadId);
        createThread(threadId);

        AtomicReference<String> approvalId = new AtomicReference<>();
        webTestClient.post()
                .uri("/api/threads/" + threadId + "/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "message": "deploy the approved plan",
                          "approvalRequired": true,
                          "approvalReason": "Need confirmation before deployment"
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.runStatus").isEqualTo("WAITING_APPROVAL")
                .jsonPath("$.approval.approvalId").value(String.class, approvalId::set);

        webTestClient.post()
                .uri("/api/threads/" + threadId + "/approvals/" + approvalId.get())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "decision": "REQUEST_CLARIFICATION",
                          "comment": "Please clarify the target environment"
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.runStatus").isEqualTo("WAITING_CLARIFICATION")
                .jsonPath("$.approval.status").isEqualTo("NEEDS_CLARIFICATION");

        webTestClient.post()
                .uri("/api/threads/" + threadId + "/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "comment": "Use the staging environment for the rollout"
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.runStatus").isEqualTo("COMPLETED")
                .jsonPath("$.approval.status").isEqualTo("NEEDS_CLARIFICATION")
                .jsonPath("$.approval.reason").isEqualTo("Use the staging environment for the rollout");
    }

    private void createThread(String threadId) {
        webTestClient.post()
                .uri("/api/threads")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "threadId": "%s"
                        }
                        """.formatted(threadId))
                .exchange()
                .expectStatus().isCreated();
    }

    private void awaitMemoryFacts(String userId, int minFacts) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            List<MemoryFact> facts = memoryStore.list(userId);
            if (facts.size() >= minFacts) {
                return;
            }
            try {
                Thread.sleep(20L);
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(memoryStore.list(userId)).hasSizeGreaterThanOrEqualTo(minFacts);
    }

    @TestConfiguration
    static class AdvancedRuntimeE2ETestConfiguration {

        @Bean
        @Primary
        ChatModel advancedRuntimeE2EChatModel() {
            return new AdvancedRuntimeE2EChatModel();
        }
    }

    private static final class AdvancedRuntimeE2EChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            String joinedPrompt = messages.stream()
                    .map(Message::getText)
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
            String latestUserMessage = messages.stream()
                    .filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast)
                    .reduce((previous, current) -> current)
                    .map(UserMessage::getText)
                    .orElse("");

            if (latestUserMessage.startsWith("Delegated subtask") && joinedPrompt.contains("Step instruction:")) {
                if (joinedPrompt.contains("Step instruction: extract risks")) {
                    return response("risk_result");
                }
                if (joinedPrompt.contains("Step instruction: collect bullets")) {
                    return response("collect_result");
                }
                if (joinedPrompt.contains("Step instruction: review findings")) {
                    return response("review_result");
                }
                if (joinedPrompt.contains("Step instruction: research data")) {
                    return response("research_result");
                }
            }

            if (latestUserMessage.startsWith("Delegated subtask")) {
                return response("subtask_result=" + latestUserMessage.substring(0, Math.min(48, latestUserMessage.length())));
            }

            List<ToolResponseMessage> toolResponses = messages.stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .toList();

            if (toolResponses.isEmpty() && latestUserMessage.toLowerCase().contains("delegate")) {
                AssistantMessage toolCallMessage = AssistantMessage.builder()
                        .content("Delegating to task tool")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-task",
                                "function",
                                "task",
                                """
                                {"action":"submit","title":"delegate work","prompt":"Coordinate delegated work and aggregate the result.","mode":"parallel","steps":[{"name":"collect","prompt":"collect bullets"},{"name":"risk","prompt":"extract risks"}],"waitForCompletion":true}
                                """
                        )))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCallMessage)));
            }

            if (!toolResponses.isEmpty()) {
                String toolResult = toolResponses.get(0).getResponses().get(0).responseData();
                return response("parent_observation=" + toolResult);
            }

            return response("Processed: " + latestUserMessage);
        }

        private ChatResponse response(String text) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }
    }
}
