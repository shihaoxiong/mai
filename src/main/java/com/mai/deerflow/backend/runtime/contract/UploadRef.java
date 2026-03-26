package com.mai.deerflow.backend.runtime.contract;

public record UploadRef(
        String name,
        String originalVirtualPath,
        String markdownVirtualPath
) {
}
