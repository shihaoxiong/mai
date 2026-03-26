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

    public UploadService(ThreadWorkspaceService threadWorkspaceService) {
        this.threadWorkspaceService = threadWorkspaceService;
    }

    public Mono<UploadRef> store(String threadId, FilePart filePart) {
        String sanitizedFilename = sanitizeFilename(filePart.filename());
        Path target = threadWorkspaceService.resolveRelativePath(threadId, WorkspaceArea.UPLOADS, sanitizedFilename);

        return filePart.transferTo(target)
                .then(Mono.fromCallable(() -> toUploadRef(threadId, target)));
    }

    public List<UploadRef> listUploads(String threadId) {
        Path uploadsRoot = threadWorkspaceService.getOrCreateWorkspace(threadId).uploadsRoot();

        if (!Files.isDirectory(uploadsRoot)) {
            return List.of();
        }

        try (Stream<Path> paths = Files.walk(uploadsRoot)) {
            return paths.filter(Files::isRegularFile)
                    .sorted(Comparator.naturalOrder())
                    .map(path -> toUploadRef(threadId, path))
                    .toList();
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to list uploads for thread " + threadId, exception);
        }
    }

    public void deleteUpload(String threadId, String filename) {
        String sanitizedFilename = sanitizeFilename(filename);
        Path target = threadWorkspaceService.resolveRelativePath(threadId, WorkspaceArea.UPLOADS, sanitizedFilename);

        try {
            if (!Files.deleteIfExists(target)) {
                throw new UploadNotFoundException(threadId, sanitizedFilename);
            }
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to delete upload %s for thread %s".formatted(sanitizedFilename, threadId), exception);
        }
    }

    private UploadRef toUploadRef(String threadId, Path file) {
        String originalVirtualPath = threadWorkspaceService.toVirtualPath(threadId, file);
        Path markdownCandidate = siblingMarkdown(file);
        String markdownVirtualPath = Files.isRegularFile(markdownCandidate)
                ? threadWorkspaceService.toVirtualPath(threadId, markdownCandidate)
                : null;

        return new UploadRef(file.getFileName().toString(), originalVirtualPath, markdownVirtualPath);
    }

    private Path siblingMarkdown(Path file) {
        String filename = file.getFileName().toString();
        int extensionSeparator = filename.lastIndexOf('.');
        String basename = extensionSeparator >= 0 ? filename.substring(0, extensionSeparator) : filename;
        return file.resolveSibling(basename + ".md");
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
