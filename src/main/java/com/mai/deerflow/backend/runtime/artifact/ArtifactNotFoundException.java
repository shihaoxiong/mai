package com.mai.deerflow.backend.runtime.artifact;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class ArtifactNotFoundException extends RuntimeException {

    public ArtifactNotFoundException(String threadId, String artifactPath) {
        super("Artifact not found for thread %s: %s".formatted(threadId, artifactPath));
    }
}
