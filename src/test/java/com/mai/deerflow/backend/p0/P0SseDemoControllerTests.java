package com.mai.deerflow.backend.p0;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class P0SseDemoControllerTests {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void shouldExposeFiniteSseStream() {
        String responseBody = webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/p0/stream")
                        .queryParam("threadId", "thread-sse")
                        .build())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(responseBody)
                .contains("event:run.started")
                .contains("event:token.delta")
                .contains("event:run.completed")
                .contains("\"threadId\":\"thread-sse\"")
                .contains("\"runId\":\"thread-sse-demo-run\"");
    }
}
