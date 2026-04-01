package com.mai.deerflow.backend.runtime.memory;

import java.util.List;

/**
 * 长期记忆的持久化抽象。
 *
 * P3-01 先聚焦“按用户维度读写事实”，为后续记忆抽取与注入策略提供稳定边界。
 */
public interface MemoryStore {

    /**
     * 查询某个用户的长期记忆；返回结果应按实现定义的相关性顺序排列。
     */
    default List<MemoryFact> list(String userId) {
        return list(userId, MemoryQuery.all());
    }

    /**
     * 按筛选条件查询某个用户的长期记忆。
     */
    List<MemoryFact> list(String userId, MemoryQuery query);

    /**
     * 写入单条长期记忆；实现可以在这里补齐 memoryId 与时间戳。
     */
    default MemoryFact save(String userId, MemoryFact fact) {
        return saveAll(userId, List.of(fact)).get(0);
    }

    /**
     * 批量写入长期记忆；适合后续记忆抽取任务一次提交多条事实。
     */
    List<MemoryFact> saveAll(String userId, List<MemoryFact> facts);

    /**
     * 删除某个用户下指定的记忆事实。
     */
    boolean delete(String userId, String memoryId);
}
