package com.mai.deerflow.backend.runtime.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mai.deerflow.backend.runtime.config.FileRuntimeConfigRepository;
import com.mai.deerflow.backend.runtime.config.RuntimeConfigProperties;
import com.mai.deerflow.backend.runtime.skill.SkillDescriptor;
import com.mai.deerflow.backend.runtime.skill.SkillRegistryService;
import com.mai.deerflow.backend.runtime.upload.DocumentMarkdownConversionService;
import com.mai.deerflow.backend.runtime.upload.UploadService;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceProperties;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeLeadAgentPromptServiceTests {

    @TempDir
    Path tempDir;

    @Test
    void shouldAssembleSkillsUploadsAndWorkspaceIntoSystemPrompt() throws Exception {
        ThreadWorkspaceProperties workspaceProperties = new ThreadWorkspaceProperties();
        workspaceProperties.setBaseDir(tempDir.resolve("threads"));
        ThreadWorkspaceService threadWorkspaceService = new ThreadWorkspaceService(workspaceProperties);
        UploadService uploadService = new UploadService(threadWorkspaceService, new DocumentMarkdownConversionService());

        RuntimeConfigProperties runtimeConfigProperties = new RuntimeConfigProperties();
        runtimeConfigProperties.setFile(tempDir.resolve("runtime-config.json"));
        SkillRegistryService skillRegistryService = new SkillRegistryService(
                new FileRuntimeConfigRepository(runtimeConfigProperties, new ObjectMapper())
        );
        skillRegistryService.replaceSkills(List.of(
                new SkillDescriptor("analysis", "Analysis", "General long-form analysis skill.", true),
                new SkillDescriptor("disabled", "Disabled", "Should not be shown.", false)
        ));

        String threadId = "prompt-service-thread";
        Path uploadPath = threadWorkspaceService.getOrCreateWorkspace(threadId).uploadsRoot().resolve("brief.md");
        Files.writeString(uploadPath, "# prompt service");

        RuntimeLeadAgentPromptService promptService = new RuntimeLeadAgentPromptService(
                threadWorkspaceService,
                uploadService,
                skillRegistryService
        );

        String systemPrompt = promptService.systemPrompt(threadId);

        assertThat(systemPrompt)
                .contains("不存在 outer runtime graph 主链路")
                .contains("Analysis [analysis]")
                .contains("/uploads/brief.md")
                .contains("/workspace")
                .contains("/outputs");
        assertThat(systemPrompt).doesNotContain("Disabled [disabled]");
    }
}
