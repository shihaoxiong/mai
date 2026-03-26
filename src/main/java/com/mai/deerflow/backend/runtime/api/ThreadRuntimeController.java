package com.mai.deerflow.backend.runtime.api;

import com.mai.deerflow.backend.runtime.contract.ThreadStateSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/threads")
/**
 * 线程运行时 API 的第一版控制器。
 *
 * 当前覆盖线程创建、查询、执行、审批、恢复和删除。
 */
public class ThreadRuntimeController {

    private final ThreadRuntimeService threadRuntimeService;

    public ThreadRuntimeController(ThreadRuntimeService threadRuntimeService) {
        this.threadRuntimeService = threadRuntimeService;
    }

    /**
     * 创建线程；若请求中未提供 threadId，则自动生成。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ThreadStateSnapshot> createThread(@RequestBody(required = false) ThreadCreateRequest request) {
        String threadId = request == null ? null : request.threadId();
        return Mono.fromCallable(() -> threadRuntimeService.createThread(threadId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 查询线程当前聚合状态。
     */
    @GetMapping("/{threadId}")
    public Mono<ThreadStateSnapshot> getThread(@PathVariable String threadId) {
        return Mono.fromCallable(() -> threadRuntimeService.getThread(threadId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 发起一次新的线程运行。
     */
    @PostMapping("/{threadId}/runs")
    public Mono<ThreadStateSnapshot> runThread(@PathVariable String threadId, @RequestBody ThreadRunRequest request) {
        return Mono.fromCallable(() -> threadRuntimeService.runThread(
                        threadId,
                        request.message(),
                        request.approvalRequired() != null && request.approvalRequired(),
                        request.approvalReason(),
                        request.userId()
                ))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 提交审批结果。
     */
    @PostMapping("/{threadId}/approvals/{approvalId}")
    public Mono<ThreadStateSnapshot> submitApproval(@PathVariable String threadId,
                                                    @PathVariable String approvalId,
                                                    @RequestBody ApprovalSubmissionRequest request) {
        return Mono.fromCallable(() -> threadRuntimeService.submitApproval(threadId, approvalId, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 在审批通过后恢复线程执行。
     */
    @PostMapping("/{threadId}/resume")
    public Mono<ThreadStateSnapshot> resumeThread(@PathVariable String threadId,
                                                  @RequestBody(required = false) ResumeThreadRequest request) {
        return Mono.fromCallable(() -> threadRuntimeService.resumeThread(threadId, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 删除线程及其本地工作区。
     */
    @DeleteMapping("/{threadId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteThread(@PathVariable String threadId) {
        return Mono.fromRunnable(() -> threadRuntimeService.deleteThread(threadId))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
