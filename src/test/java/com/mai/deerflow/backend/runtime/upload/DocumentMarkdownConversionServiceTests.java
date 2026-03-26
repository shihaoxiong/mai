package com.mai.deerflow.backend.runtime.upload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentMarkdownConversionServiceTests {

    @TempDir
    Path tempDir;

    private final DocumentMarkdownConversionService conversionService = new DocumentMarkdownConversionService();

    @Test
    void shouldConvertPlainTextFileToMarkdownSibling() throws Exception {
        Path source = tempDir.resolve("brief.txt");
        Files.writeString(source, "hello deerflow");

        Path markdown = conversionService.convert(source).orElseThrow();

        assertThat(markdown.getFileName().toString()).isEqualTo("brief.md");
        assertThat(Files.readString(markdown)).isEqualTo("hello deerflow");
    }

    @Test
    void shouldTreatMarkdownUploadAsOwnMarkdownPath() throws Exception {
        Path source = tempDir.resolve("notes.md");
        Files.writeString(source, "# notes");

        Path markdown = conversionService.convert(source).orElseThrow();

        assertThat(markdown).isEqualTo(source);
    }

    @Test
    void shouldCreatePlaceholderMarkdownForOfficeStyleDocument() throws Exception {
        Path source = tempDir.resolve("report.pdf");
        Files.writeString(source, "binary-placeholder");

        Path markdown = conversionService.convert(source).orElseThrow();

        assertThat(markdown.getFileName().toString()).isEqualTo("report.md");
        assertThat(Files.readString(markdown)).contains("Automatic Markdown extraction");
    }
}
