package com.mai.deerflow.backend.runtime.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Repository
/**
 * 基于单个 JSON 文件的动态配置仓库实现。
 *
 * 这是当前阶段最轻量的配置持久化方案，便于后续平滑替换成 JDBC 或配置中心版本。
 */
public class FileRuntimeConfigRepository implements RuntimeConfigRepository {

    private static final TypeReference<LinkedHashMap<String, JsonNode>> STORE_TYPE = new TypeReference<>() {
    };

    private final RuntimeConfigProperties properties;
    private final ObjectMapper objectMapper;

    public FileRuntimeConfigRepository(RuntimeConfigProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public synchronized <T> Optional<T> find(String key, Class<T> type) {
        JsonNode value = readStore().get(key);
        if (value == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.treeToValue(value, type));
        }
        catch (Exception exception) {
            throw new IllegalStateException("Failed to deserialize runtime config for key " + key, exception);
        }
    }

    @Override
    public synchronized <T> Optional<T> find(String key, TypeReference<T> typeReference) {
        JsonNode value = readStore().get(key);
        if (value == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.convertValue(value, typeReference));
        }
        catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Failed to deserialize runtime config for key " + key, exception);
        }
    }

    @Override
    public synchronized <T> T save(String key, T value) {
        Map<String, JsonNode> store = readStore();
        store.put(key, objectMapper.valueToTree(value));
        writeStore(store);
        return value;
    }

    @Override
    public synchronized boolean delete(String key) {
        Map<String, JsonNode> store = readStore();
        JsonNode removed = store.remove(key);
        writeStore(store);
        return removed != null;
    }

    @Override
    public synchronized Map<String, JsonNode> list() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(readStore()));
    }

    private Map<String, JsonNode> readStore() {
        Path file = properties.getFile().toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }

        try {
            return objectMapper.readValue(file.toFile(), STORE_TYPE);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to read runtime config file " + file, exception);
        }
    }

    private void writeStore(Map<String, JsonNode> store) {
        Path file = properties.getFile().toAbsolutePath().normalize();
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), store);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to write runtime config file " + file, exception);
        }
    }
}
