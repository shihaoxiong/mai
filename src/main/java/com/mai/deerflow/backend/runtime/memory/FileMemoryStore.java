package com.mai.deerflow.backend.runtime.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
/**
 * 基于本地 JSON 文件的长期记忆存储实现。
 *
 * 每个用户的记忆单独存一份文件，既方便后续替换为数据库实现，也能避免 thread 级目录清理误删长期记忆。
 */
public class FileMemoryStore implements MemoryStore, MemoryProfileStore {

    private static final Comparator<MemoryFact> DEFAULT_ORDER = Comparator
            .comparingDouble(MemoryFact::confidence)
            .reversed()
            .thenComparing(MemoryFact::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(MemoryFact::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(MemoryFact::memoryId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final MemoryStoreProperties properties;
    private final ObjectMapper objectMapper;

    public FileMemoryStore(MemoryStoreProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public synchronized List<MemoryFact> list(String userId, MemoryQuery query) {
        String normalizedUserId = normalizeUserId(userId);
        MemoryQuery effectiveQuery = query == null ? MemoryQuery.all() : query;

        return orderedFacts(readFacts(normalizedUserId)).stream()
                .filter(fact -> effectiveQuery.minConfidence() == null || fact.confidence() >= effectiveQuery.minConfidence())
                .limit(effectiveQuery.limit() == null ? Long.MAX_VALUE : effectiveQuery.limit())
                .toList();
    }

    @Override
    public synchronized List<MemoryFact> saveAll(String userId, List<MemoryFact> facts) {
        String normalizedUserId = normalizeUserId(userId);
        if (facts == null || facts.isEmpty()) {
            return List.of();
        }

        Map<String, MemoryFact> factIndex = new LinkedHashMap<>();
        orderedFacts(readFacts(normalizedUserId)).forEach(fact -> factIndex.put(fact.memoryId(), fact));

        Instant now = Instant.now();
        List<MemoryFact> persistedFacts = new ArrayList<>();
        for (MemoryFact fact : facts) {
            MemoryFact existingFact = hasText(fact.memoryId()) ? factIndex.get(fact.memoryId().trim()) : null;
            MemoryFact normalizedFact = normalizeFact(fact, existingFact, now);
            factIndex.put(normalizedFact.memoryId(), normalizedFact);
            persistedFacts.add(normalizedFact);
        }

        List<MemoryFact> storedFacts = orderedFacts(factIndex.values());
        writeFacts(normalizedUserId, storedFacts);
        return List.copyOf(persistedFacts);
    }

    @Override
    public synchronized boolean delete(String userId, String memoryId) {
        String normalizedUserId = normalizeUserId(userId);
        String normalizedMemoryId = normalizeMemoryId(memoryId);
        Map<String, MemoryFact> factIndex = new LinkedHashMap<>();
        orderedFacts(readFacts(normalizedUserId)).forEach(fact -> factIndex.put(fact.memoryId(), fact));

        MemoryFact removedFact = factIndex.remove(normalizedMemoryId);
        if (removedFact == null) {
            return false;
        }

        if (factIndex.isEmpty()) {
            deleteFactsFile(normalizedUserId);
            return true;
        }

        writeFacts(normalizedUserId, orderedFacts(factIndex.values()));
        return true;
    }

    @Override
    public synchronized StructuredMemoryProfile loadProfile(String userId) {
        return readStoredMemories(normalizeUserId(userId)).profile();
    }

    @Override
    public synchronized StructuredMemoryProfile saveProfile(String userId, StructuredMemoryProfile profile) {
        String normalizedUserId = normalizeUserId(userId);
        StoredUserMemories existingMemories = readStoredMemories(normalizedUserId);
        StructuredMemoryProfile normalizedProfile = profile == null ? StructuredMemoryProfile.empty() : profile;
        writeStoredMemories(normalizedUserId, new StoredUserMemories(
                normalizedUserId,
                normalizedProfile,
                existingMemories.facts()
        ));
        return normalizedProfile;
    }

    private List<MemoryFact> readFacts(String userId) {
        return readStoredMemories(userId).facts();
    }

    private void writeFacts(String userId, List<MemoryFact> facts) {
        StoredUserMemories existingMemories = readStoredMemories(userId);
        writeStoredMemories(userId, new StoredUserMemories(
                userId,
                existingMemories.profile(),
                facts
        ));
    }

    private void deleteFactsFile(String userId) {
        try {
            Files.deleteIfExists(factsFile(userId));
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to delete memories for user " + userId, exception);
        }
    }

    private StoredUserMemories readStoredMemories(String userId) {
        Path factsFile = factsFile(userId);
        if (!Files.isRegularFile(factsFile)) {
            return new StoredUserMemories(userId, StructuredMemoryProfile.empty(), List.of());
        }

        try {
            StoredUserMemories storedUserMemories = objectMapper.readValue(factsFile.toFile(), StoredUserMemories.class);
            return storedUserMemories == null
                    ? new StoredUserMemories(userId, StructuredMemoryProfile.empty(), List.of())
                    : storedUserMemories;
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to read memories for user " + userId, exception);
        }
    }

    private void writeStoredMemories(String userId, StoredUserMemories storedUserMemories) {
        Path factsFile = factsFile(userId);
        Path tempFile = factsFile.resolveSibling(factsFile.getFileName() + ".tmp");
        try {
            if (factsFile.getParent() != null) {
                Files.createDirectories(factsFile.getParent());
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), storedUserMemories);
            try {
                Files.move(tempFile, factsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (IOException ignored) {
                Files.move(tempFile, factsFile, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to write memories for user " + userId, exception);
        }
    }

    private MemoryFact normalizeFact(MemoryFact fact, MemoryFact existingFact, Instant now) {
        String memoryId = hasText(fact.memoryId()) ? fact.memoryId().trim() : UUID.randomUUID().toString();
        String createdAt = hasText(fact.createdAt())
                ? fact.createdAt().trim()
                : existingFact != null && hasText(existingFact.createdAt())
                ? existingFact.createdAt().trim()
                : now.toString();
        String sourceThreadId = hasText(fact.sourceThreadId())
                ? fact.sourceThreadId().trim()
                : existingFact == null ? null : existingFact.sourceThreadId();

        return new MemoryFact(
                memoryId,
                fact.category(),
                fact.content().trim(),
                fact.confidence(),
                sourceThreadId,
                createdAt,
                now.toString(),
                fact.attributes()
        );
    }

    private List<MemoryFact> orderedFacts(Iterable<MemoryFact> facts) {
        List<MemoryFact> ordered = new ArrayList<>();
        facts.forEach(ordered::add);
        ordered.sort(DEFAULT_ORDER);
        return List.copyOf(ordered);
    }

    private Path factsFile(String userId) {
        Path baseDir = properties.getBaseDir().toAbsolutePath().normalize();
        Path file = baseDir.resolve(encodedUserId(userId) + ".json").normalize();
        if (!file.startsWith(baseDir)) {
            throw new IllegalStateException("Memory store path escapes base directory for user " + userId);
        }
        return file;
    }

    private String normalizeUserId(String userId) {
        if (!hasText(userId)) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        return userId.trim();
    }

    private String normalizeMemoryId(String memoryId) {
        if (!hasText(memoryId)) {
            throw new IllegalArgumentException("memoryId must not be blank");
        }
        return memoryId.trim();
    }

    private String encodedUserId(String userId) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(userId.getBytes(StandardCharsets.UTF_8));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 每个用户的长期记忆文件结构。
     */
    record StoredUserMemories(
            String userId,
            StructuredMemoryProfile profile,
            List<MemoryFact> facts
    ) {

        StoredUserMemories {
            profile = profile == null ? StructuredMemoryProfile.empty() : profile;
            facts = facts == null ? List.of() : List.copyOf(facts);
        }
    }
}
