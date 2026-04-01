package com.mai.deerflow.backend.runtime.api;

/**
 * 线程级轻量上下文。
 *
 * 当前先保存 `userId`，为长期记忆抽取和后续注入策略提供线程到用户的绑定关系。
 */
public record ThreadContextMetadata(String userId) {
}
