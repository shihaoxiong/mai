package com.mai.deerflow.backend.runtime.contract;

/**
 * 线程事件流对外暴露的标准事件类型。
 *
 * `wireName` 会直接作为 SSE `event` 字段输出，
 * 因此一旦前端开始依赖，应尽量保持稳定。
 */
public enum RunEventType {
    /** 一次新的 run 已开始执行。 */
    RUN_STARTED("run.started"),
    /** 模型正在持续输出文本增量。 */
    TOKEN_DELTA("token.delta"),
    /** 工具调用已发起。 */
    TOOL_CALL_STARTED("tool.call.started"),
    /** 工具调用已完成。 */
    TOOL_CALL_COMPLETED("tool.call.completed"),
    /** 子任务已开始。 */
    SUBTASK_STARTED("subtask.started"),
    /** 子任务状态有更新。 */
    SUBTASK_UPDATED("subtask.updated"),
    /** 当前 run 进入待审批状态。 */
    APPROVAL_REQUIRED("approval.required"),
    /** 线程产生了新的产物文件。 */
    ARTIFACT_CREATED("artifact.created"),
    /** 记忆抽取或记忆持久化任务已安排。 */
    MEMORY_SCHEDULED("memory.scheduled"),
    /** 当前 run 已成功完成。 */
    RUN_COMPLETED("run.completed"),
    /** 当前 run 已失败结束。 */
    RUN_FAILED("run.failed");

    private final String wireName;

    RunEventType(String wireName) {
        this.wireName = wireName;
    }

    /**
     * 返回对外协议层使用的事件名。
     */
    public String wireName() {
        return wireName;
    }
}
