package com.mai.deerflow.backend.runtime.artifact;

import java.nio.file.Path;

public record ArtifactContent(
        Path path,
        String filename,
        String contentType
) {
}
