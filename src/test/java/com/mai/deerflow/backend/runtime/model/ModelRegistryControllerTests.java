package com.mai.deerflow.backend.runtime.model;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ModelRegistryControllerTests {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void shouldExposeModelListEndpoint() {
        webTestClient.get()
                .uri("/api/models")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].id").isEqualTo("fallback-chat")
                .jsonPath("$[0].provider").isEqualTo("internal")
                .jsonPath("$[0].enabled").isEqualTo(true);
    }
}
