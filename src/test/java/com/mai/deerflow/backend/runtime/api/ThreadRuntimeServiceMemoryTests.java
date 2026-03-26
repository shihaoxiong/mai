package com.mai.deerflow.backend.runtime.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mai.deerflow.backend.runtime.agent.LeadAgentFactory;
import com.mai.deerflow.backend.runtime.agent.RuntimeAgentEnhancementService;
import com.mai.deerflow.backend.runtime.artifact.ArtifactService;
import com.mai.deerflow.backend.runtime.contract.RunEventType;
import com.mai.deerflow.backend.runtime.contract.RunStatus;
import com.mai.deerflow.backend.runtime.contract.ThreadStateSnapshot;
import com.mai.deerflow.backend.runtime.event.ThreadEventService;
import com.mai.deerflow.backend.runtime.graph.RuntimeGraphFactory;
import com.mai.deerflow.backend.runtime.memory.MemoryFact;
import com.mai.deerflow.backend.runtime.memory.MemoryExtractorJob;
import com.mai.deerflow.backend.runtime.memory.MemoryInjectionProperties;
import com.mai.deerflow.backend.runtime.memory.MemoryInjectionService;
import com.mai.deerflow.backend.runtime.memory.MemoryStoreProperties;
import com.mai.deerflow.backend.runtime.memory.MemoryStore;
import com.mai.deerflow.backend.runtime.memory.FileMemoryStore;
import com.mai.deerflow.backend.runtime.state.RunStateMachine;
import com.mai.deerflow.backend.runtime.upload.DocumentMarkdownConversionService;
import com.mai.deerflow.backend.runtime.upload.UploadService;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceProperties;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import reactor.test.StepVerifier;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ThreadRuntimeServiceMemoryTests {

    @TempDir
    Path tempDir;

    @Test
    void shouldPersistExtractedMemoriesAfterCompletedRun() {
        ThreadWorkspaceProperties workspaceProperties = new ThreadWorkspaceProperties();
        workspaceProperties.setBaseDir(tempDir.resolve("threads-success"));
        ThreadWorkspaceService threadWorkspaceService = new ThreadWorkspaceService(workspaceProperties);
        DocumentMarkdownConversionService documentMarkdownConversionService = new DocumentMarkdownConversionService();
        UploadService uploadService = new UploadService(threadWorkspaceService, documentMarkdownConversionService);
        ArtifactService artifactService = new ArtifactService(threadWorkspaceService);
        MemoryStoreProperties memoryStoreProperties = new MemoryStoreProperties();
        memoryStoreProperties.setBaseDir(tempDir.resolve("memory-success"));
        FileMemoryStore fileMemoryStore = new FileMemoryStore(memoryStoreProperties, new ObjectMapper());
        RuntimeGraphFactory runtimeGraphFactory = new RuntimeGraphFactory(
                threadWorkspaceService,
                uploadService,
                artifactService,
                new MemoryInjectionService(fileMemoryStore, new MemoryInjectionProperties())
        );
        LeadAgentFactory leadAgentFactory = new LeadAgentFactory();
        RuntimeAgentEnhancementService runtimeAgentEnhancementService =
                new RuntimeAgentEnhancementService(new ObjectMapper());
        ChatModel chatModel = new FallbackChatModelConfiguration().fallbackChatModel();
        RunStateMachine runStateMachine = new RunStateMachine();
        ThreadEventService threadEventService = new ThreadEventService();
        MemoryExtractorJob memoryExtractorJob = new MemoryExtractorJob(fileMemoryStore, Runnable::run);

        ThreadRuntimeService threadRuntimeService = new ThreadRuntimeService(
                threadWorkspaceService,
                runtimeGraphFactory,
                leadAgentFactory,
                runtimeAgentEnhancementService,
                chatModel,
                runStateMachine,
                new ObjectMapper(),
                uploadService,
                artifactService,
                threadEventService,
                memoryExtractorJob
        );

        String threadId = "memory-success-" + UUID.randomUUID();
        String userId = "memory-user-" + UUID.randomUUID();
        try {
            threadRuntimeService.createThread(threadId);
            ThreadStateSnapshot snapshot = threadRuntimeService.runThread(
                    threadId,
                    "I use Java 17 locally. Please keep comments in Chinese and prefer small refactors.",
                    false,
                    null,
                    userId
            );

            assertThat(snapshot.runStatus()).isEqualTo(RunStatus.COMPLETED);
            assertThat(fileMemoryStore.list(userId))
                    .extracting(MemoryFact::category)
                    .containsExactly("preference", "fact", "context");

            StepVerifier.create(threadEventService.stream(threadId)
                            .take(3)
                            .map(event -> event.data().eventType()))
                    .expectNext(RunEventType.RUN_STARTED, RunEventType.MEMORY_SCHEDULED, RunEventType.RUN_COMPLETED)
                    .verifyComplete();
        }
        finally {
            threadRuntimeService.deleteThread(threadId);
        }
    }

    @Test
    void shouldCompleteRunEvenWhenMemoryExtractionFails() {
        ThreadWorkspaceProperties workspaceProperties = new ThreadWorkspaceProperties();
        workspaceProperties.setBaseDir(tempDir.resolve("threads"));
        ThreadWorkspaceService threadWorkspaceService = new ThreadWorkspaceService(workspaceProperties);
        DocumentMarkdownConversionService documentMarkdownConversionService = new DocumentMarkdownConversionService();
        UploadService uploadService = new UploadService(threadWorkspaceService, documentMarkdownConversionService);
        ArtifactService artifactService = new ArtifactService(threadWorkspaceService);
        MemoryStore failingMemoryStore = new MemoryStore() {
            @Override
            public List<MemoryFact> list(String userId, com.mai.deerflow.backend.runtime.memory.MemoryQuery query) {
                return List.of();
            }

            @Override
            public List<MemoryFact> saveAll(String userId, List<MemoryFact> facts) {
                throw new IllegalStateException("memory store unavailable");
            }

            @Override
            public boolean delete(String userId, String memoryId) {
                return false;
            }
        };
        RuntimeGraphFactory runtimeGraphFactory = new RuntimeGraphFactory(
                threadWorkspaceService,
                uploadService,
                artifactService,
                new MemoryInjectionService(failingMemoryStore, new MemoryInjectionProperties())
        );
        LeadAgentFactory leadAgentFactory = new LeadAgentFactory();
        RuntimeAgentEnhancementService runtimeAgentEnhancementService =
                new RuntimeAgentEnhancementService(new ObjectMapper());
        ChatModel chatModel = new FallbackChatModelConfiguration().fallbackChatModel();
        RunStateMachine runStateMachine = new RunStateMachine();
        ThreadEventService threadEventService = new ThreadEventService();
        MemoryExtractorJob memoryExtractorJob = new MemoryExtractorJob(failingMemoryStore, Runnable::run);

        ThreadRuntimeService threadRuntimeService = new ThreadRuntimeService(
                threadWorkspaceService,
                runtimeGraphFactory,
                leadAgentFactory,
                runtimeAgentEnhancementService,
                chatModel,
                runStateMachine,
                new ObjectMapper(),
                uploadService,
                artifactService,
                threadEventService,
                memoryExtractorJob
        );

        String threadId = "memory-failure-" + UUID.randomUUID();
        try {
            threadRuntimeService.createThread(threadId);
            ThreadStateSnapshot snapshot = threadRuntimeService.runThread(
                    threadId,
                    "I use Java 17 locally. Please keep comments in Chinese.",
                    false,
                    null,
                    "memory-user"
            );

            assertThat(snapshot.runStatus()).isEqualTo(RunStatus.COMPLETED);

            StepVerifier.create(threadEventService.stream(threadId)
                            .take(3)
                            .map(event -> event.data().eventType()))
                    .expectNext(RunEventType.RUN_STARTED, RunEventType.MEMORY_SCHEDULED, RunEventType.RUN_COMPLETED)
                    .verifyComplete();
        }
        finally {
            threadRuntimeService.deleteThread(threadId);
        }
    }
}
