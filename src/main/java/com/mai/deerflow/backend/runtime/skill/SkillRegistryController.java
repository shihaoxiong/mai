package com.mai.deerflow.backend.runtime.skill;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/skills")
public class SkillRegistryController {

    private final SkillRegistryService skillRegistryService;

    public SkillRegistryController(SkillRegistryService skillRegistryService) {
        this.skillRegistryService = skillRegistryService;
    }

    @GetMapping
    public List<SkillDescriptor> listSkills() {
        return skillRegistryService.listSkills();
    }

    @GetMapping("/{skillId}")
    public SkillDescriptor getSkill(@PathVariable String skillId) {
        return skillRegistryService.getSkill(skillId);
    }

    @PostMapping("/{skillId}/enable")
    public SkillDescriptor enableSkill(@PathVariable String skillId) {
        return skillRegistryService.enableSkill(skillId);
    }

    @PostMapping("/{skillId}/disable")
    public SkillDescriptor disableSkill(@PathVariable String skillId) {
        return skillRegistryService.disableSkill(skillId);
    }
}
