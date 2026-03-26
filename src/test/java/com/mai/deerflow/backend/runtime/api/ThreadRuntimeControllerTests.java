package com.mai.deerflow.backend.runtime.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.concurrent.atomic.AtomicReference;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ThreadRuntimeControllerTests {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void shouldCreateRunRecoverQueryAndDeleteThread() {
        webTestClient.post()
                .uri("/api/threads")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "threadId": "api-thread"
                        }
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.threadId").isEqualTo("api-thread")
                .jsonPath("$.runStatus").isEqualTo("IDLE")
                .jsonPath("$.workspace.workspacePath").exists();

        webTestClient.post()
                .uri("/api/threads/api-thread/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "message": "analyze the uploaded brief"
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.threadId").isEqualTo("api-thread")
                .jsonPath("$.runId").isNotEmpty()
                .jsonPath("$.runStatus").isEqualTo("COMPLETED")
                .jsonPath("$.title").isEqualTo("analyze the uploaded brief")
                .jsonPath("$.suggestions[0]").isEqualTo("continue this thread");

        webTestClient.get()
                .uri("/api/threads/api-thread")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.threadId").isEqualTo("api-thread")
                .jsonPath("$.runId").isNotEmpty()
                .jsonPath("$.runStatus").isEqualTo("COMPLETED");

        webTestClient.delete()
                .uri("/api/threads/api-thread")
                .exchange()
                .expectStatus().isNoContent();

        webTestClient.get()
                .uri("/api/threads/api-thread")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void shouldRequireApprovalAndResumeExecution() {
        webTestClient.post()
                .uri("/api/threads")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "threadId": "approval-thread"
                        }
                        """)
                .exchange()
                .expectStatus().isCreated();

        AtomicReference<String> approvalId = new AtomicReference<>();
        webTestClient.post()
                .uri("/api/threads/approval-thread/runs")
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
                .uri("/api/threads/approval-thread/approvals/" + approvalId.get())
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
                .uri("/api/threads/approval-thread/resume")
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
}
