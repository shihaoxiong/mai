package com.mai.deerflow.backend.runtime.contract;

public record ArtifactRef(
        String name,
        String virtualPath,
        String contentType
) {
}
