package com.mai.deerflow.backend.runtime.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Optional;

public interface RuntimeConfigRepository {

    <T> Optional<T> find(String key, Class<T> type);

    <T> Optional<T> find(String key, TypeReference<T> typeReference);

    <T> T save(String key, T value);

    boolean delete(String key);

    Map<String, JsonNode> list();
}
