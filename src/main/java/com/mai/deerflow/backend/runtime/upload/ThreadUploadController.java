package com.mai.deerflow.backend.runtime.upload;

import com.mai.deerflow.backend.runtime.contract.UploadRef;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@RestController
@RequestMapping("/api/threads/{threadId}/uploads")
/**
 * 线程上传文件 API。
 */
public class ThreadUploadController {

    private final UploadService uploadService;

    public ThreadUploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    /**
     * 上传一个或多个文件到线程 uploads 目录。
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<List<UploadRef>> uploadFiles(@PathVariable String threadId, @RequestPart("files") Flux<FilePart> files) {
        return files.concatMap(file -> uploadService.store(threadId, file))
                .collectList();
    }

    /**
     * 列出线程当前上传文件及其 Markdown 派生视图。
     */
    @GetMapping("/list")
    public Mono<List<UploadRef>> listUploads(@PathVariable String threadId) {
        return Mono.fromCallable(() -> uploadService.listUploads(threadId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 删除指定上传文件。
     */
    @DeleteMapping("/{filename}")
    public Mono<Void> deleteUpload(@PathVariable String threadId, @PathVariable String filename) {
        return Mono.fromRunnable(() -> uploadService.deleteUpload(threadId, filename))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
