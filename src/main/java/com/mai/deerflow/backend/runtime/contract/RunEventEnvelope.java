package com.mai.deerflow.backend.runtime.contract;

/**
 * 线程事件流统一使用的事件包裹结构。
 *
 * @param payload 事件对应的业务数据，允许根据事件类型变化
 */
public record RunEventEnvelope<T>(
        String threadId,
        String runId,
        RunEventType eventType,
        T payload
) {
}
