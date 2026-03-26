package com.mai.deerflow.backend.runtime.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mai.deerflow.backend.runtime.config.RuntimeConfigRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SkillRegistryService {

    static final String SKILLS_CONFIG_KEY = "skills.registry";

    private static final TypeReference<List<SkillDescriptor>> SKILL_LIST_TYPE = new TypeReference<>() {
    };

    private final RuntimeConfigRepository runtimeConfigRepository;

    public SkillRegistryService(RuntimeConfigRepository runtimeConfigRepository) {
        this.runtimeConfigRepository = runtimeConfigRepository;
    }

    public List<SkillDescriptor> listSkills() {
        return runtimeConfigRepository.find(SKILLS_CONFIG_KEY, SKILL_LIST_TYPE)
                .orElseGet(this::defaultSkills);
    }

    public SkillDescriptor getSkill(String skillId) {
        return listSkills().stream()
                .filter(skill -> skill.id().equals(skillId))
                .findFirst()
                .orElseThrow(() -> new SkillNotFoundException(skillId));
    }

    public SkillDescriptor enableSkill(String skillId) {
        return updateEnabled(skillId, true);
    }

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
