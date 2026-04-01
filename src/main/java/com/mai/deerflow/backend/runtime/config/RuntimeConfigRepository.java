package com.mai.deerflow.backend.runtime.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Optional;

/**
 * 平台动态配置仓库抽象。
 *
 * 后续模型、MCP、技能等配置都通过它读写，避免把配置持久化细节散落到业务服务中。
 */
public interface RuntimeConfigRepository {

    /**
     * 按 key 读取指定类型配置。
     */
    <T> Optional<T> find(String key, Class<T> type);

    /**
     * 按 key 读取泛型配置结构。
     */
    <T> Optional<T> find(String key, TypeReference<T> typeReference);

    /**
     * 保存或覆盖一条配置。
     */
    <T> T save(String key, T value);

    /**
     * 删除一条配置。
     */
    boolean delete(String key);

    /**
     * 列出当前仓库中所有配置条目。
     */
    Map<String, JsonNode> list();
}
