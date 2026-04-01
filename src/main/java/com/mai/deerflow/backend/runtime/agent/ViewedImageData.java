package com.mai.deerflow.backend.runtime.agent;

/**
 * `view_image` 工具返回给运行时拦截器的图片负载。
 */
public record ViewedImageData(
        String path,
        String mimeType,
        String base64
) {
}
