package com.mai.deerflow.backend.runtime.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.autoconfigure.exclude=org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration"
)
class ThreadRuntimeControllerTests {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void shouldCreateRunRecoverQueryAndDeleteThread() {
        String threadId = "api-thread-" + UUID.randomUUID();
        webTestClient.post()
                .uri("/api/threads")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "threadId": "%s"
                        }
                        """.formatted(threadId))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.threadId").isEqualTo(threadId)
                .jsonPath("$.runStatus").isEqualTo("IDLE")
                .jsonPath("$.workspace.workspacePath").exists();

        webTestClient.post()
                .uri("/api/threads/" + threadId + "/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "message": "analyze the uploaded brief"
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.threadId").isEqualTo(threadId)
                .jsonPath("$.runId").isNotEmpty()
                .jsonPath("$.runStatus").isEqualTo("COMPLETED")
                .jsonPath("$.messages[0].role").isEqualTo("user")
                .jsonPath("$.messages[0].content").isEqualTo("analyze the uploaded brief")
                .jsonPath("$.messages[1].role").isEqualTo("assistant")
                .jsonPath("$.messages[1].content").isEqualTo("Processed: analyze the uploaded brief")
                .jsonPath("$.title").isEqualTo("analyze the uploaded brief")
                .jsonPath("$.suggestions[0]").isEqualTo("ask for risks and next steps")
                .jsonPath("$.suggestions[1]").isEqualTo("request a concise action checklist");

        webTestClient.get()
                .uri("/api/threads/" + threadId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.threadId").isEqualTo(threadId)
                .jsonPath("$.runId").isNotEmpty()
                .jsonPath("$.runStatus").isEqualTo("COMPLETED");

        webTestClient.delete()
                .uri("/api/threads/" + threadId)
                .exchange()
                .expectStatus().isNoContent();

        webTestClient.get()
                .uri("/api/threads/" + threadId)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void shouldRequireApprovalAndResumeExecution() {
        String threadId = "approval-thread-" + UUID.randomUUID();
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

        AtomicReference<String> approvalId = new AtomicReference<>();
        webTestClient.post()
                .uri("/api/threads/" + threadId + "/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "message": "run after approval",
                          "approvalRequired": true,
                          "approvalReason": "Need human approval before execution"
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.runStatus").isEqualTo("WAITING_APPROVAL")
                .jsonPath("$.approval.status").isEqualTo("WAITING")
                .jsonPath("$.approval.reason").isEqualTo("Need human approval before execution")
                .jsonPath("$.approval.approvalId").value(String.class, approvalId::set);

        webTestClient.post()
                .uri("/api/threads/" + threadId + "/approvals/" + approvalId.get())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "decision": "APPROVE",
                          "comment": "approved by reviewer"
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.approval.status").isEqualTo("APPROVED")
                .jsonPath("$.approval.reason").isEqualTo("approved by reviewer");

        webTestClient.post()
                .uri("/api/threads/" + threadId + "/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "comment": "resume now"
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.runStatus").isEqualTo("COMPLETED")
                .jsonPath("$.approval.status").isEqualTo("APPROVED");
    }

    @Test
    void shouldRequestClarificationBeforeResumeExecution() {
        String threadId = "clarification-thread-" + UUID.randomUUID();
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
                .jsonPath("$.approval.status").isEqualTo("NEEDS_CLARIFICATION")
                .jsonPath("$.approval.reason").isEqualTo("Please clarify the target environment");

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

    @Test
    void shouldSupportSseThreadRunOnSameRunsEndpoint() {
        String threadId = "sse-run-thread-" + UUID.randomUUID();
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

        String responseBody = webTestClient.post()
                .uri("/api/threads/" + threadId + "/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue("""
                        {
                          "message": "stream this run via sse"
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(responseBody)
                .contains("event:run.started")
                .contains("event:token.delta")
                .contains("event:run.completed")
                .contains("\"threadId\":\"" + threadId + "\"")
                .contains("Processed: stream this run via sse");
    }

    @Test
    void shouldRejectUnsupportedRunOptionsWithBadRequest() {
        String threadId = "bad-run-options-thread-" + UUID.randomUUID();
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

        webTestClient.post()
                .uri("/api/threads/" + threadId + "/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "message": "run with unsupported reasoning",
                          "reasoning_effort": "high"
                        }
                        """)
                .exchange()
                .expectStatus().isBadRequest();
    }
}
