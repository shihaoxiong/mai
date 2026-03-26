package com.mai.deerflow.backend.runtime.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mai.deerflow.backend.runtime.config.FileRuntimeConfigRepository;
import com.mai.deerflow.backend.runtime.config.RuntimeConfigProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillRegistryServiceTests {

    @TempDir
    Path tempDir;

    private SkillRegistryService skillRegistryService;

    @BeforeEach
    void setUp() {
        RuntimeConfigProperties properties = new RuntimeConfigProperties();
        properties.setFile(tempDir.resolve("runtime-config.json"));
        FileRuntimeConfigRepository repository = new FileRuntimeConfigRepository(properties, new ObjectMapper());
        skillRegistryService = new SkillRegistryService(repository);
    }

    @Test
    void shouldReturnDefaultSkillList() {
        assertThat(skillRegistryService.listSkills())
                .hasSize(1)
                .first()
                .extracting(SkillDescriptor::id)
                .isEqualTo("analysis");
    }

    @Test
    void shouldEnableAndDisableSkill() {
        SkillDescriptor enabled = skillRegistryService.enableSkill("analysis");
        assertThat(enabled.enabled()).isTrue();

        SkillDescriptor disabled = skillRegistryService.disableSkill("analysis");
        assertThat(disabled.enabled()).isFalse();
    }

    @Test
    void shouldThrowWhenSkillIsMissing() {
        assertThatThrownBy(() -> skillRegistryService.getSkill("missing"))
                .isInstanceOf(SkillNotFoundException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void shouldUseConfiguredSkillListWhenPresent() {
        List<SkillDescriptor> configuredSkills = List.of(
                new SkillDescriptor("search", "Search", "Search skill", true),
                new SkillDescriptor("analysis", "Analysis", "Analysis skill", true)
        );

        skillRegistryService.replaceSkills(configuredSkills);

        assertThat(skillRegistryService.listSkills()).containsExactlyElementsOf(configuredSkills);
    }
}
