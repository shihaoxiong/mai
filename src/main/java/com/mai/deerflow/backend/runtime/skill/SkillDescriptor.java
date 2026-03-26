package com.mai.deerflow.backend.runtime.skill;

public record SkillDescriptor(
        String id,
        String name,
        String description,
        boolean enabled
) {
}
