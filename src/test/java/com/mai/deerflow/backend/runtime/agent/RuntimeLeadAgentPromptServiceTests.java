package com.mai.deerflow.backend.runtime.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mai.deerflow.backend.runtime.config.FileRuntimeConfigRepository;
import com.mai.deerflow.backend.runtime.config.RuntimeConfigProperties;
import com.mai.deerflow.backend.runtime.memory.FileMemoryStore;
import com.mai.deerflow.backend.runtime.memory.MemoryHistoryProfile;
import com.mai.deerflow.backend.runtime.memory.MemoryProfileSection;
import com.mai.deerflow.backend.runtime.memory.MemoryStoreProperties;
import com.mai.deerflow.backend.runtime.memory.MemoryUserProfile;
import com.mai.deerflow.backend.runtime.memory.StructuredMemoryProfile;
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
                new SkillDescriptor("analysis", "Analysis", "General long-form analysis skill.", true, "/skills/analysis/SKILL.md"),
                new SkillDescriptor("disabled", "Disabled", "Should not be shown.", false)
        ));

        String threadId = "prompt-service-thread";
        Path uploadPath = threadWorkspaceService.getOrCreateWorkspace(threadId).uploadsRoot().resolve("brief.md");
        Files.writeString(uploadPath, "# prompt service");
        MemoryStoreProperties memoryStoreProperties = new MemoryStoreProperties();
        memoryStoreProperties.setBaseDir(tempDir.resolve("memory"));
        FileMemoryStore memoryStore = new FileMemoryStore(memoryStoreProperties, new ObjectMapper());
        memoryStore.saveProfile(
                "prompt-user",
                new StructuredMemoryProfile(
                        "1.0",
                        "2026-03-30T00:00:00Z",
                        new MemoryUserProfile(
                                new MemoryProfileSection("Maintains the backend runtime", "2026-03-30T00:00:00Z"),
                                MemoryProfileSection.empty(),
                                new MemoryProfileSection("Currently aligning prompt behavior", "2026-03-30T00:00:00Z")
                        ),
                        new MemoryHistoryProfile(
                                new MemoryProfileSection("Recently completed memory queue work", "2026-03-30T00:00:00Z"),
                                MemoryProfileSection.empty(),
                                new MemoryProfileSection("Prefers incremental delivery", "2026-03-30T00:00:00Z")
                        )
                )
        );

        RuntimeLeadAgentPromptService promptService = new RuntimeLeadAgentPromptService(
                threadWorkspaceService,
                uploadService,
                skillRegistryService
        );
        RuntimeLeadAgentPromptProperties properties = new RuntimeLeadAgentPromptProperties();
        properties.setAgentSoul("你是一个谨慎推进、重视验证的 runtime lead agent。");
        promptService.setPromptProperties(properties);
        promptService.setMemoryProfileStore(memoryStore);

        String systemPrompt = promptService.systemPrompt(threadId, com.mai.deerflow.backend.runtime.api.RuntimeRunOptions.defaults(), "prompt-user");

        assertThat(systemPrompt)
                .contains("lead agent 主链路")
                .contains("outer graph")
                .contains("<agent_soul>")
                .contains("谨慎推进、重视验证")
                .contains("<memory>")
                .contains("Maintains the backend runtime")
                .contains("Analysis [analysis]")
                .contains("location=/skills/analysis/SKILL.md")
                .contains("Progressive Loading")
                .contains("research_citation_rules")
                .contains("Sources")
                .contains("output_rules")
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
