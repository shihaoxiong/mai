package com.mai.deerflow.backend.runtime.artifact;

import com.mai.deerflow.backend.runtime.contract.ArtifactRef;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceService;
import com.mai.deerflow.backend.runtime.workspace.WorkspaceArea;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Service
/**
 * 线程产物管理服务。
 *
 * 产物来源于 `outputs` 目录，服务负责把目录中的实际文件转换成对外可用的元数据和内容视图。
 */
public class ArtifactService {

    private final ThreadWorkspaceService threadWorkspaceService;

    public ArtifactService(ThreadWorkspaceService threadWorkspaceService) {
        this.threadWorkspaceService = threadWorkspaceService;
    }

    /**
     * 列出线程当前 outputs 目录下的全部产物。
     */
    public List<ArtifactRef> listArtifacts(String threadId) {
        Path outputsRoot = threadWorkspaceService.getOrCreateWorkspace(threadId).outputsRoot();
        if (!Files.isDirectory(outputsRoot)) {
            return List.of();
        }

        try (Stream<Path> paths = Files.walk(outputsRoot)) {
            return paths.filter(Files::isRegularFile)
                    .sorted(Comparator.naturalOrder())
                    .map(path -> new ArtifactRef(
                            path.getFileName().toString(),
                            threadWorkspaceService.toVirtualPath(threadId, path),
                            Optional.ofNullable(probeContentType(path)).orElse("application/octet-stream")
                    ))
                    .toList();
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to list artifacts for thread " + threadId, exception);
        }
    }

    /**
     * 按相对路径读取单个产物内容元数据。
     */
    public ArtifactContent getArtifact(String threadId, String artifactPath) {
        Path resolved = threadWorkspaceService.resolveRelativePath(threadId, WorkspaceArea.OUTPUTS, normalizeArtifactPath(artifactPath));
        if (!Files.isRegularFile(resolved)) {
            throw new ArtifactNotFoundException(threadId, artifactPath);
        }

        return new ArtifactContent(
                resolved,
                resolved.getFileName().toString(),
                Optional.ofNullable(probeContentType(resolved)).orElse("application/octet-stream")
        );
    }

    private String normalizeArtifactPath(String artifactPath) {
        if (artifactPath == null || artifactPath.isBlank()) {
            throw new IllegalArgumentException("artifactPath must not be blank");
        }
        String normalized = artifactPath.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private String probeContentType(Path path) {
        try {
            return Files.probeContentType(path);
        }
        catch (IOException exception) {
            return null;
        }
    }
}
