package com.mai.deerflow.backend.runtime.artifact;

import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceService;
import com.mai.deerflow.backend.runtime.workspace.WorkspaceArea;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ArtifactControllerTests {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ThreadWorkspaceService threadWorkspaceService;

    @Test
    void shouldListAndReadArtifactsWithinThreadScope() throws Exception {
        threadWorkspaceService.createWorkspace("artifact-thread");
        Path artifact = threadWorkspaceService.resolveRelativePath("artifact-thread", WorkspaceArea.OUTPUTS, "reports/summary.txt");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "artifact-content");

        webTestClient.get()
                .uri("/api/threads/artifact-thread/artifacts/list")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].name").isEqualTo("summary.txt")
                .jsonPath("$[0].virtualPath").isEqualTo("/outputs/reports/summary.txt");

        webTestClient.get()
                .uri("/api/threads/artifact-thread/artifacts/reports/summary.txt")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_PLAIN)
                .expectBody(String.class).isEqualTo("artifact-content");
    }

    @Test
    void shouldNotExposeArtifactsAcrossThreads() throws Exception {
        threadWorkspaceService.createWorkspace("artifact-thread-a");
        Path artifact = threadWorkspaceService.resolveRelativePath("artifact-thread-a", WorkspaceArea.OUTPUTS, "private.txt");
        Files.writeString(artifact, "private");

        threadWorkspaceService.createWorkspace("artifact-thread-b");

        webTestClient.get()
                .uri("/api/threads/artifact-thread-b/artifacts/private.txt")
                .exchange()
                .expectStatus().isNotFound();
    }
}
