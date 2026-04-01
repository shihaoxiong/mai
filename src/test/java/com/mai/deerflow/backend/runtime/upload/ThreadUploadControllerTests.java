package com.mai.deerflow.backend.runtime.upload;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ThreadUploadControllerTests {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void shouldUploadListDeleteAndExposeUploadsOnThreadState() {
        webTestClient.post()
                .uri("/api/threads")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "threadId": "upload-thread"
                        }
                        """)
                .exchange()
                .expectStatus().isCreated();

        MultipartBodyBuilder multipartBodyBuilder = new MultipartBodyBuilder();
        multipartBodyBuilder.part("files", new ByteArrayResource("hello deerflow".getBytes()) {
            @Override
            public String getFilename() {
                return "brief.txt";
            }
        });

        webTestClient.post()
                .uri("/api/threads/upload-thread/uploads")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(multipartBodyBuilder.build()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].name").isEqualTo("brief.txt")
                .jsonPath("$[0].originalVirtualPath").isEqualTo("/uploads/brief.txt")
                .jsonPath("$[0].markdownVirtualPath").isEqualTo("/uploads/brief.md");

        webTestClient.get()
                .uri("/api/threads/upload-thread/uploads/list")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].name").isEqualTo("brief.txt")
                .jsonPath("$[0].markdownVirtualPath").isEqualTo("/uploads/brief.md");

        webTestClient.get()
                .uri("/api/threads/upload-thread")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.uploads[0].name").isEqualTo("brief.txt")
                .jsonPath("$.uploads[0].originalVirtualPath").isEqualTo("/uploads/brief.txt")
                .jsonPath("$.uploads[0].markdownVirtualPath").isEqualTo("/uploads/brief.md");

        webTestClient.delete()
                .uri("/api/threads/upload-thread/uploads/brief.txt")
                .exchange()
                .expectStatus().isOk();

        webTestClient.get()
                .uri("/api/threads/upload-thread/uploads/list")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$").isArray();
    }
}
