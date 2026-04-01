package com.mai.deerflow.backend.runtime.agent;

import org.springframework.ai.tool.ToolCallback;

/**
 * deferred tool 的轻量注册项。
 */
public record RuntimeDeferredToolEntry(
        String name,
        String description,
        ToolCallback tool
) {
}
