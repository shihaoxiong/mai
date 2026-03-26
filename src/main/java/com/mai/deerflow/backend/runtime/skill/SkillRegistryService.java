package com.mai.deerflow.backend.runtime.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mai.deerflow.backend.runtime.config.RuntimeConfigRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
/**
 * 技能注册中心。
 *
 * 当前负责技能列表、详情查询和启用开关，后续可扩展到安装与版本管理。
 */
public class SkillRegistryService {

    static final String SKILLS_CONFIG_KEY = "skills.registry";

    private static final TypeReference<List<SkillDescriptor>> SKILL_LIST_TYPE = new TypeReference<>() {
    };

    private final RuntimeConfigRepository runtimeConfigRepository;

    public SkillRegistryService(RuntimeConfigRepository runtimeConfigRepository) {
        this.runtimeConfigRepository = runtimeConfigRepository;
    }

    /**
     * 返回当前技能列表；若没有外部配置，则返回默认技能。
     */
    public List<SkillDescriptor> listSkills() {
        return runtimeConfigRepository.find(SKILLS_CONFIG_KEY, SKILL_LIST_TYPE)
                .orElseGet(this::defaultSkills);
    }

    /**
     * 获取单个技能详情。
     */
    public SkillDescriptor getSkill(String skillId) {
        return listSkills().stream()
                .filter(skill -> skill.id().equals(skillId))
                .findFirst()
                .orElseThrow(() -> new SkillNotFoundException(skillId));
    }

    /**
     * 启用指定技能。
     */
    public SkillDescriptor enableSkill(String skillId) {
        return updateEnabled(skillId, true);
    }

    /**
     * 禁用指定技能。
     */
    public SkillDescriptor disableSkill(String skillId) {
        return updateEnabled(skillId, false);
    }

    public List<SkillDescriptor> replaceSkills(List<SkillDescriptor> skillDescriptors) {
        return runtimeConfigRepository.save(SKILLS_CONFIG_KEY, List.copyOf(skillDescriptors));
    }

    private SkillDescriptor updateEnabled(String skillId, boolean enabled) {
        List<SkillDescriptor> updatedSkills = listSkills().stream()
                .map(skill -> skill.id().equals(skillId)
                        ? new SkillDescriptor(skill.id(), skill.name(), skill.description(), enabled)
                        : skill)
                .toList();

        SkillDescriptor updatedSkill = updatedSkills.stream()
                .filter(skill -> skill.id().equals(skillId))
                .findFirst()
                .orElseThrow(() -> new SkillNotFoundException(skillId));

        replaceSkills(updatedSkills);
        return updatedSkill;
    }

    private List<SkillDescriptor> defaultSkills() {
        return List.of(
                new SkillDescriptor(
                        "analysis",
                        "Analysis",
                        "General long-form analysis skill for DeerFlow-style research tasks.",
                        false
                )
        );
    }
}
