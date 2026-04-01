package com.mai.deerflow.backend.runtime.skill;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/skills")
/**
 * 技能注册中心 HTTP 接口。
 */
public class SkillRegistryController {

    private final SkillRegistryService skillRegistryService;

    public SkillRegistryController(SkillRegistryService skillRegistryService) {
        this.skillRegistryService = skillRegistryService;
    }

    /**
     * 列出技能。
     */
    @GetMapping
    public List<SkillDescriptor> listSkills() {
        return skillRegistryService.listSkills();
    }

    /**
     * 获取单个技能详情。
     */
    @GetMapping("/{skillId}")
    public SkillDescriptor getSkill(@PathVariable String skillId) {
        return skillRegistryService.getSkill(skillId);
    }

    /**
     * 启用技能。
     */
    @PostMapping("/{skillId}/enable")
    public SkillDescriptor enableSkill(@PathVariable String skillId) {
        return skillRegistryService.enableSkill(skillId);
    }

    /**
     * 禁用技能。
     */
    @PostMapping("/{skillId}/disable")
    public SkillDescriptor disableSkill(@PathVariable String skillId) {
        return skillRegistryService.disableSkill(skillId);
    }
}
