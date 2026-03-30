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
                .contains("lead agent 主链路")
                .contains("outer graph")
                .contains("Analysis [analysis]")
                .contains("/uploads/brief.md")
                .contains("/workspace")
                .contains("/outputs");
        assertThat(systemPrompt).doesNotContain("Disabled [disabled]");
    }

    @Test
    void shouldAssembleTurnContextBlockWithoutMutatingPersistentMessages() throws Exception {
        ThreadWorkspaceProperties workspaceProperties = new ThreadWorkspaceProperties();
        workspaceProperties.setBaseDir(tempDir.resolve("threads-turn"));
        ThreadWorkspaceService threadWorkspaceService = new ThreadWorkspaceService(workspaceProperties);
        UploadService uploadService = new UploadService(threadWorkspaceService, new DocumentMarkdownConversionService());

        RuntimeConfigProperties runtimeConfigProperties = new RuntimeConfigProperties();
        runtimeConfigProperties.setFile(tempDir.resolve("runtime-config-turn.json"));
        SkillRegistryService skillRegistryService = new SkillRegistryService(
                new FileRuntimeConfigRepository(runtimeConfigProperties, new ObjectMapper())
        );

        String threadId = "turn-context-thread";
        Path uploadPath = threadWorkspaceService.getOrCreateWorkspace(threadId).uploadsRoot().resolve("brief.md");
        Files.writeString(uploadPath, "# hello deerflow");

        RuntimeLeadAgentPromptService promptService = new RuntimeLeadAgentPromptService(
                threadWorkspaceService,
                uploadService,
                skillRegistryService
        );

        String turnContextBlock = promptService.turnContextBlock(threadId);

        assertThat(turnContextBlock)
                .contains("<thread_data>")
                .contains("workspace_path:")
                .contains("uploads_path:")
                .contains("outputs_path:")
                .contains("<uploaded_files>")
                .contains("/uploads/brief.md");
    }
}
