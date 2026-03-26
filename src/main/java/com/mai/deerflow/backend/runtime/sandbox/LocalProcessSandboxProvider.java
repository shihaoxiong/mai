package com.mai.deerflow.backend.runtime.sandbox;

import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceService;
import com.mai.deerflow.backend.runtime.workspace.WorkspaceArea;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

@Component
public class LocalProcessSandboxProvider implements SandboxProvider {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final ThreadWorkspaceService threadWorkspaceService;

    public LocalProcessSandboxProvider(ThreadWorkspaceService threadWorkspaceService) {
        this.threadWorkspaceService = threadWorkspaceService;
    }

    @Override
    public CommandExecutionResult execute(CommandExecutionRequest request) {
        if (request.command() == null || request.command().isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }

        Path workingDirectory = threadWorkspaceService.resolveRelativePath(
                request.threadId(),
                request.area(),
                request.workingDirectory()
        );

        ProcessBuilder processBuilder = new ProcessBuilder(request.command());
        processBuilder.directory(workingDirectory.toFile());

        Duration timeout = request.timeout() == null ? DEFAULT_TIMEOUT : request.timeout();

        try {
            Process process = processBuilder.start();
            boolean completed = process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);

            if (!completed) {
                process.destroyForcibly();
                process.waitFor();
            }

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

            return new CommandExecutionResult(process.exitValue(), stdout, stderr, !completed);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Local sandbox command interrupted", exception);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Local sandbox command failed to start", exception);
        }
    }

    @Override
    public List<String> listDirectory(String threadId, WorkspaceArea area, String directoryPath) {
        Path directory = threadWorkspaceService.resolveRelativePath(threadId, area, directoryPath);
        try {
            if (!Files.isDirectory(directory)) {
                return List.of();
            }
            try (var children = Files.list(directory)) {
                return children
                        .map(path -> path.getFileName().toString())
                        .sorted(Comparator.naturalOrder())
                        .toList();
            }
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to list directory " + directory, exception);
        }
    }

    @Override
    public String readFile(String threadId, WorkspaceArea area, String filePath) {
        Path file = threadWorkspaceService.resolveRelativePath(threadId, area, filePath);
        try {
            return Files.readString(file);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to read file " + file, exception);
        }
    }

    @Override
    public void writeFile(String threadId, WorkspaceArea area, String filePath, String content) {
        Path file = threadWorkspaceService.resolveRelativePath(threadId, area, filePath);
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to write file " + file, exception);
        }
    }

    @Override
    public void replaceInFile(String threadId, WorkspaceArea area, String filePath, String target, String replacement) {
        String original = readFile(threadId, area, filePath);
        if (!original.contains(target)) {
            throw new IllegalArgumentException("target text not found in file: " + target);
        }
        writeFile(threadId, area, filePath, original.replace(target, replacement));
    }
}
