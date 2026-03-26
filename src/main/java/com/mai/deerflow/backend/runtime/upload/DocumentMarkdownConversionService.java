package com.mai.deerflow.backend.runtime.upload;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

@Service
public class DocumentMarkdownConversionService {

    private static final Set<String> PLAIN_TEXT_EXTENSIONS = Set.of(
            "txt", "text", "log", "csv", "json", "xml", "yaml", "yml"
    );

    private static final Set<String> PLACEHOLDER_DOCUMENT_EXTENSIONS = Set.of(
            "pdf", "ppt", "pptx", "xls", "xlsx", "doc", "docx"
    );

    public Optional<Path> convert(Path sourceFile) {
        String extension = extensionOf(sourceFile);

        try {
            if ("md".equals(extension)) {
                return Optional.of(sourceFile);
            }
            if (PLAIN_TEXT_EXTENSIONS.contains(extension)) {
                Path markdownPath = siblingMarkdown(sourceFile);
                Files.writeString(markdownPath, Files.readString(sourceFile));
                return Optional.of(markdownPath);
            }
            if (PLACEHOLDER_DOCUMENT_EXTENSIONS.contains(extension)) {
                Path markdownPath = siblingMarkdown(sourceFile);
                Files.writeString(markdownPath, """
                        # Imported Document

                        Original file: %s

                        Automatic Markdown extraction for this file type is not yet configured in the current environment.
                        The original file has been retained for later processing.
                        """.formatted(sourceFile.getFileName()));
                return Optional.of(markdownPath);
            }
            return Optional.empty();
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to convert file to markdown: " + sourceFile, exception);
        }
    }

    public boolean isDerivedMarkdown(Path file) {
        String filename = file.getFileName().toString();
        if (!filename.endsWith(".md")) {
            return false;
        }

        String basename = filename.substring(0, filename.length() - 3);
        Path parent = file.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return false;
        }

        try (var siblings = Files.list(parent)) {
            return siblings
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.equals(file))
                    .map(path -> path.getFileName().toString())
                    .anyMatch(name -> name.startsWith(basename + ".") && !name.endsWith(".md"));
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to inspect sibling files for " + file, exception);
        }
    }

    public Path siblingMarkdown(Path sourceFile) {
        String filename = sourceFile.getFileName().toString();
        int extensionSeparator = filename.lastIndexOf('.');
        String basename = extensionSeparator >= 0 ? filename.substring(0, extensionSeparator) : filename;
        return sourceFile.resolveSibling(basename + ".md");
    }

    private String extensionOf(Path file) {
        String filename = file.getFileName().toString();
        int extensionSeparator = filename.lastIndexOf('.');
        if (extensionSeparator < 0 || extensionSeparator == filename.length() - 1) {
            return "";
        }
        return filename.substring(extensionSeparator + 1).toLowerCase();
    }
}
