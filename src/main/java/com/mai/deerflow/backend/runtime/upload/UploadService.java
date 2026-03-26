package com.mai.deerflow.backend.runtime.upload;

import com.mai.deerflow.backend.runtime.contract.UploadRef;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceService;
import com.mai.deerflow.backend.runtime.workspace.WorkspaceArea;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Service
public class UploadService {

    private final ThreadWorkspaceService threadWorkspaceService;
    private final DocumentMarkdownConversionService documentMarkdownConversionService;

    public UploadService(ThreadWorkspaceService threadWorkspaceService,
                         DocumentMarkdownConversionService documentMarkdownConversionService) {
        this.threadWorkspaceService = threadWorkspaceService;
        this.documentMarkdownConversionService = documentMarkdownConversionService;
    }

    public Mono<UploadRef> store(String threadId, FilePart filePart) {
        String sanitizedFilename = sanitizeFilename(filePart.filename());
        Path target = threadWorkspaceService.resolveRelativePath(threadId, WorkspaceArea.UPLOADS, sanitizedFilename);

        return filePart.transferTo(target)
                .then(Mono.fromCallable(() -> {
                    Path markdownPath = documentMarkdownConversionService.convert(target).orElse(null);
                    return toUploadRef(threadId, target, markdownPath);
                }));
    }

    public List<UploadRef> listUploads(String threadId) {
        Path uploadsRoot = threadWorkspaceService.getOrCreateWorkspace(threadId).uploadsRoot();

        if (!Files.isDirectory(uploadsRoot)) {
            return List.of();
        }

        try (Stream<Path> paths = Files.walk(uploadsRoot)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> !documentMarkdownConversionService.isDerivedMarkdown(path))
                    .sorted(Comparator.naturalOrder())
                    .map(path -> toUploadRef(threadId, path, resolveMarkdownPath(path)))
                    .toList();
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to list uploads for thread " + threadId, exception);
        }
    }

    public void deleteUpload(String threadId, String filename) {
        String sanitizedFilename = sanitizeFilename(filename);
        Path target = threadWorkspaceService.resolveRelativePath(threadId, WorkspaceArea.UPLOADS, sanitizedFilename);
        Path markdownPath = resolveMarkdownPath(target);

        try {
            if (!Files.deleteIfExists(target)) {
                throw new UploadNotFoundException(threadId, sanitizedFilename);
            }
            if (markdownPath != null && !markdownPath.equals(target)) {
                Files.deleteIfExists(markdownPath);
            }
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to delete upload %s for thread %s".formatted(sanitizedFilename, threadId), exception);
        }
    }

    private UploadRef toUploadRef(String threadId, Path file, Path markdownPath) {
        String originalVirtualPath = threadWorkspaceService.toVirtualPath(threadId, file);
        String markdownVirtualPath = markdownPath != null && Files.isRegularFile(markdownPath)
                ? threadWorkspaceService.toVirtualPath(threadId, markdownPath)
                : null;

        return new UploadRef(file.getFileName().toString(), originalVirtualPath, markdownVirtualPath);
    }

    private Path resolveMarkdownPath(Path file) {
        if (file.getFileName().toString().endsWith(".md")) {
            return file;
        }

        Path markdownPath = documentMarkdownConversionService.siblingMarkdown(file);
        return Files.isRegularFile(markdownPath) ? markdownPath : null;
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("filename must not be blank");
        }
        Path path = Path.of(filename).getFileName();
        if (path == null || path.toString().isBlank()) {
            throw new IllegalArgumentException("filename must not be blank");
        }
        return path.toString();
    }
}
