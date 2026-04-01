package com.mai.deerflow.backend.runtime.contract;

/**
 * 一次线程运行在平台侧的生命周期状态。
 */
public enum RunStatus {
    /** 线程已创建，但当前没有正在执行的 run。 */
    IDLE,
    /** run 已启动，正在执行图节点或 agent 推理。 */
    RUNNING,
    /** run 已暂停，等待人工审批。 */
    WAITING_APPROVAL,
    /** run 已暂停，等待用户补充澄清信息。 */
    WAITING_CLARIFICATION,
    /** run 执行失败，等待重试或人工处理。 */
    FAILED,
    /** run 已正常完成。 */
    COMPLETED
}
