package com.mai.deerflow.backend.runtime.model;

import java.util.List;

/**
 * 平台对外暴露的模型描述信息。
 */
public record ModelDescriptor(
        String id,
        String provider,
        boolean enabled,
        List<String> capabilities,
        String description
) {
}
