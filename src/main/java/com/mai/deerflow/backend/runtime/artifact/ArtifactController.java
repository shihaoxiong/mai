package com.mai.deerflow.backend.runtime.artifact;

import com.mai.deerflow.backend.runtime.contract.ArtifactRef;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@RestController
@RequestMapping("/api/threads/{threadId}/artifacts")
/**
 * 线程产物 HTTP 访问入口。
 */
public class ArtifactController {

    private final ArtifactService artifactService;

    public ArtifactController(ArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    /**
     * 列出线程当前可见的产物元数据。
     */
    @GetMapping("/list")
    public Mono<List<ArtifactRef>> listArtifacts(@PathVariable String threadId) {
        return Mono.fromCallable(() -> artifactService.listArtifacts(threadId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 读取或下载线程中的单个产物。
     */
    @GetMapping("/{*artifactPath}")
    public Mono<ResponseEntity<Resource>> getArtifact(@PathVariable String threadId,
                                                      @PathVariable String artifactPath,
                                                      @RequestParam(defaultValue = "false") boolean download) {
        return Mono.fromCallable(() -> artifactService.getArtifact(threadId, artifactPath))
                .subscribeOn(Schedulers.boundedElastic())
                .map(artifactContent -> {
                    ContentDisposition disposition = (download
                            ? ContentDisposition.attachment()
                            : ContentDisposition.inline())
                            .filename(artifactContent.filename())
                            .build();

                    return ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType(artifactContent.contentType()))
                            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                            .body(new FileSystemResource(artifactContent.path()));
                });
    }
}
