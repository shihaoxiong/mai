package com.mai.deerflow.backend.runtime.workspace;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

@Service
public class ThreadWorkspaceService {

    private static final String VIRTUAL_ROOT_PREFIX = "/";

    private final ThreadWorkspaceProperties properties;

    public ThreadWorkspaceService(ThreadWorkspaceProperties properties) {
        this.properties = properties;
    }

    public ThreadWorkspace createWorkspace(String threadId) {
        String validatedThreadId = validateThreadId(threadId);
        Path threadRoot = threadRoot(validatedThreadId);

        try {
            Files.createDirectories(threadRoot);
            for (WorkspaceArea area : WorkspaceArea.values()) {
                Files.createDirectories(threadRoot.resolve(area.directoryName()));
            }
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to create thread workspace for " + validatedThreadId, exception);
        }

        return toWorkspace(validatedThreadId);
    }

    public ThreadWorkspace getWorkspace(String threadId) {
        String validatedThreadId = validateThreadId(threadId);
        if (!Files.isDirectory(threadRoot(validatedThreadId))) {
            throw new IllegalStateException("Thread workspace does not exist: " + validatedThreadId);
        }
        return toWorkspace(validatedThreadId);
    }

    public ThreadWorkspace getOrCreateWorkspace(String threadId) {
        String validatedThreadId = validateThreadId(threadId);
        return Files.isDirectory(threadRoot(validatedThreadId))
                ? toWorkspace(validatedThreadId)
                : createWorkspace(validatedThreadId);
    }

    public boolean exists(String threadId) {
        return Files.isDirectory(threadRoot(validateThreadId(threadId)));
    }

    public void deleteWorkspace(String threadId) {
        String validatedThreadId = validateThreadId(threadId);
        Path threadRoot = threadRoot(validatedThreadId);

        if (!Files.exists(threadRoot)) {
            return;
        }

        try {
            Files.walkFileTree(threadRoot, new RecursiveDeleteVisitor());
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to delete thread workspace for " + validatedThreadId, exception);
        }
    }

    public Path resolveVirtualPath(String threadId, String virtualPath) {
        String validatedThreadId = validateThreadId(threadId);
        String normalizedVirtualPath = normalizeVirtualPath(virtualPath);
        String[] segments = normalizedVirtualPath.split("/");

        if (segments.length == 0 || segments[0].isBlank()) {
            throw new InvalidWorkspacePathException("Virtual path must start with workspace root");
        }

        WorkspaceArea area = workspaceArea(segments[0]);
        String relativePath = segments.length == 1
                ? ""
                : String.join("/", Arrays.copyOfRange(segments, 1, segments.length));

        return resolveRelativePath(validatedThreadId, area, relativePath);
    }

    public Path resolveRelativePath(String threadId, WorkspaceArea area, String relativePath) {
        Objects.requireNonNull(area, "area must not be null");

        ThreadWorkspace workspace = getOrCreateWorkspace(threadId);
        Path basePath = workspace.rootFor(area);
        Path candidate = relativePath == null || relativePath.isBlank()
                ? basePath
                : basePath.resolve(relativePath).normalize();

        if (!candidate.startsWith(basePath)) {
            throw new InvalidWorkspacePathException("Virtual path escapes thread workspace: " + relativePath);
        }

        return candidate;
    }

    public String toVirtualPath(String threadId, Path realPath) {
        ThreadWorkspace workspace = getWorkspace(threadId);
        Path normalizedRealPath = realPath.toAbsolutePath().normalize();

        for (WorkspaceArea area : WorkspaceArea.values()) {
            Path areaRoot = workspace.rootFor(area).toAbsolutePath().normalize();
            if (normalizedRealPath.startsWith(areaRoot)) {
                Path relativePath = areaRoot.relativize(normalizedRealPath);
                return relativePath.toString().isBlank()
                        ? VIRTUAL_ROOT_PREFIX + area.directoryName()
                        : VIRTUAL_ROOT_PREFIX + area.directoryName() + "/" + relativePath.toString().replace('\\', '/');
            }
        }

        throw new InvalidWorkspacePathException("Real path is outside thread workspace: " + realPath);
    }

    private ThreadWorkspace toWorkspace(String threadId) {
        Path threadRoot = threadRoot(threadId);
        return new ThreadWorkspace(
                threadId,
                threadRoot,
                threadRoot.resolve(WorkspaceArea.WORKSPACE.directoryName()),
                threadRoot.resolve(WorkspaceArea.UPLOADS.directoryName()),
                threadRoot.resolve(WorkspaceArea.OUTPUTS.directoryName())
        );
    }

    private Path threadRoot(String threadId) {
        return properties.getBaseDir().toAbsolutePath().normalize().resolve(threadId).normalize();
    }

    private String validateThreadId(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId must not be blank");
        }
        if (threadId.contains("/") || threadId.contains("\\") || threadId.contains("..")) {
            throw new IllegalArgumentException("threadId contains illegal path characters: " + threadId);
        }
        return threadId;
    }

    private String normalizeVirtualPath(String virtualPath) {
        if (virtualPath == null || virtualPath.isBlank()) {
            throw new InvalidWorkspacePathException("Virtual path must not be blank");
        }

        String normalized = virtualPath.replace('\\', '/').trim();
        while (normalized.startsWith(VIRTUAL_ROOT_PREFIX)) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private WorkspaceArea workspaceArea(String rootSegment) {
        return Arrays.stream(WorkspaceArea.values())
                .filter(area -> area.directoryName().equals(rootSegment.toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElseThrow(() -> new InvalidWorkspacePathException("Unsupported virtual root: " + rootSegment));
    }

    private static final class RecursiveDeleteVisitor extends SimpleFileVisitor<Path> {

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            Files.deleteIfExists(file);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
            if (exception != null) {
                throw exception;
            }
            Files.deleteIfExists(directory);
            return FileVisitResult.CONTINUE;
        }
    }
}
