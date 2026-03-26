package com.mai.deerflow.backend.runtime.skill;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SkillRegistryControllerTests {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void shouldListAndToggleSkill() {
        webTestClient.post()
                .uri("/api/skills/analysis/disable")
                .exchange()
                .expectStatus().isOk();

        webTestClient.get()
                .uri("/api/skills")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].id").isEqualTo("analysis")
                .jsonPath("$[0].enabled").isEqualTo(false);

        webTestClient.post()
                .uri("/api/skills/analysis/enable")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("analysis")
                .jsonPath("$.enabled").isEqualTo(true);

        webTestClient.get()
                .uri("/api/skills/analysis")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("analysis")
                .jsonPath("$.enabled").isEqualTo(true);

        webTestClient.post()
                .uri("/api/skills/analysis/disable")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("analysis")
                .jsonPath("$.enabled").isEqualTo(false);
    }
}
