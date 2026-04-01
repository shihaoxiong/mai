package com.mai.deerflow.backend.runtime.agent;

/**
 * `read_thread_file` 工具输入。
 */
public record ReadThreadFileRequest(
        String path,
        Integer maxChars
) {
}
