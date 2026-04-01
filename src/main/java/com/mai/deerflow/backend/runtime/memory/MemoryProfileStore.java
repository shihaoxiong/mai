package com.mai.deerflow.backend.runtime.memory;

/**
 * 结构化长期记忆档案的持久化抽象。
 *
 * 与 `MemoryStore` 的 facts 列表并行存在：
 * - `MemoryStore` 继续负责可筛选的原子事实
 * - `MemoryProfileStore` 负责更接近 DeerFlow 的结构化用户画像 / 历史摘要
 */
public interface MemoryProfileStore {

    /**
     * 读取某个用户的结构化记忆档案；若不存在则返回空档案。
     */
    StructuredMemoryProfile loadProfile(String userId);

    /**
     * 持久化某个用户的结构化记忆档案。
     */
    StructuredMemoryProfile saveProfile(String userId, StructuredMemoryProfile profile);
}
