package com.mai.deerflow.backend.runtime.contract;

/**
 * 上传文件及其派生 Markdown 文件的引用信息。
 */
public record UploadRef(
        String name,
        String originalVirtualPath,
        String markdownVirtualPath
) {
}
