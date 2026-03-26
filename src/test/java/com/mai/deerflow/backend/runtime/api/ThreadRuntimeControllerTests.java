package com.mai.deerflow.backend.runtime.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ThreadRuntimeControllerTests {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void shouldCreateRunQueryAndDeleteThread() {
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
}
