package com.mai.deerflow.backend.runtime.contract;

/**
 * 线程产物的轻量元数据视图。
 */
public record ArtifactRef(
        String name,
        String virtualPath,
        String contentType
) {
}
