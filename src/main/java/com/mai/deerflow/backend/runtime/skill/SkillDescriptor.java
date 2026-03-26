package com.mai.deerflow.backend.runtime.skill;

/**
 * 技能中心对外暴露的技能描述。
 */
public record SkillDescriptor(
        String id,
        String name,
        String description,
        boolean enabled
) {
}
