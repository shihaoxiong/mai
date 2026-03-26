package com.mai.deerflow.backend.runtime.platform;

import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceService;
import com.mai.deerflow.backend.runtime.workspace.WorkspaceArea;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformApiIntegrationTests {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ThreadWorkspaceService threadWorkspaceService;

    @Test
    void shouldCoverCorePlatformApisInOneFlow() throws Exception {
        webTestClient.get()
                .uri("/api/models")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].id").isEqualTo("fallback-chat");

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
                .jsonPath("$[0].id").isEqualTo("filesystem");

        webTestClient.get()
                .uri("/api/mcp/config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].command").isEqualTo("npx");

        webTestClient.get()
                .uri("/api/skills")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].id").isEqualTo("analysis");

        webTestClient.post()
                .uri("/api/skills/analysis/disable")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.enabled").isEqualTo(false);

        webTestClient.post()
                .uri("/api/skills/analysis/enable")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.enabled").isEqualTo(true);

        webTestClient.get()
                .uri("/api/skills/analysis")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.enabled").isEqualTo(true);

        webTestClient.post()
                .uri("/api/threads")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "threadId": "platform-thread"
                        }
                        """)
                .exchange()
                .expectStatus().isCreated();

        MultipartBodyBuilder multipartBodyBuilder = new MultipartBodyBuilder();
        multipartBodyBuilder.part("files", new ByteArrayResource("hello platform".getBytes()) {
            @Override
            public String getFilename() {
                return "platform.txt";
            }
        });

        webTestClient.post()
                .uri("/api/threads/platform-thread/uploads")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(multipartBodyBuilder.build()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].name").isEqualTo("platform.txt")
                .jsonPath("$[0].markdownVirtualPath").isEqualTo("/uploads/platform.md");

        webTestClient.get()
                .uri("/api/threads/platform-thread/uploads/list")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].name").isEqualTo("platform.txt");

        Path artifactPath = threadWorkspaceService.resolveRelativePath(
                "platform-thread",
                WorkspaceArea.OUTPUTS,
                "reports/result.txt"
        );
        Files.createDirectories(artifactPath.getParent());
        Files.writeString(artifactPath, "platform-artifact");

        webTestClient.get()
                .uri("/api/threads/platform-thread/artifacts/list")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].virtualPath").isEqualTo("/outputs/reports/result.txt");

        webTestClient.get()
                .uri("/api/threads/platform-thread/artifacts/reports/result.txt")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("platform-artifact");
    }
}
