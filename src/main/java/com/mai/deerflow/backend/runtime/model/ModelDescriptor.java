package com.mai.deerflow.backend.runtime.model;

import java.util.List;

public record ModelDescriptor(
        String id,
        String provider,
        boolean enabled,
        List<String> capabilities,
        String description
) {
}
