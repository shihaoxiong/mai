package com.mai.deerflow.backend.runtime.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpConfigControllerTests {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void shouldReplaceAndQueryMcpConfigs() {
        webTestClient.put()
                .uri("/api/mcp/config")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        [
                          {
                            "id": "filesystem",
                            "enabled": true,
                            "transport": "stdio",
                            "command": "npx",
                            "args": ["-y", "@modelcontextprotocol/server-filesystem", "."],
                            "env": {
                              "NODE_ENV": "test"
                            }
                          }
                        ]
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].id").isEqualTo("filesystem")
                .jsonPath("$[0].enabled").isEqualTo(true);

        webTestClient.get()
                .uri("/api/mcp/config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].id").isEqualTo("filesystem")
                .jsonPath("$[0].transport").isEqualTo("stdio")
                .jsonPath("$[0].command").isEqualTo("npx");
    }
}
