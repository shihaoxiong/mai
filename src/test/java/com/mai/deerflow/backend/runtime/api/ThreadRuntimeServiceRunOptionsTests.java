package com.mai.deerflow.backend.runtime.api;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mai.deerflow.backend.runtime.agent.LeadAgentFactory;
import com.mai.deerflow.backend.runtime.agent.RuntimeAgentEnhancementService;
import com.mai.deerflow.backend.runtime.agent.RuntimeLeadAgentPromptService;
import com.mai.deerflow.backend.runtime.artifact.ArtifactService;
import com.mai.deerflow.backend.runtime.checkpoint.RuntimeCheckpointProperties;
import com.mai.deerflow.backend.runtime.checkpoint.RuntimeCheckpointService;
import com.mai.deerflow.backend.runtime.config.FileRuntimeConfigRepository;
import com.mai.deerflow.backend.runtime.config.RuntimeConfigProperties;
import com.mai.deerflow.backend.runtime.contract.ThreadStateSnapshot;
import com.mai.deerflow.backend.runtime.event.ThreadEventService;
import com.mai.deerflow.backend.runtime.memory.FileMemoryStore;
import com.mai.deerflow.backend.runtime.memory.MemoryExtractorJob;
import com.mai.deerflow.backend.runtime.memory.MemoryInjectionProperties;
import com.mai.deerflow.backend.runtime.memory.MemoryInjectionService;
import com.mai.deerflow.backend.runtime.memory.MemoryStoreProperties;
import com.mai.deerflow.backend.runtime.model.ModelDescriptor;
import com.mai.deerflow.backend.runtime.model.ModelRegistryService;
import com.mai.deerflow.backend.runtime.postrun.PostRunGenerationService;
import com.mai.deerflow.backend.runtime.skill.SkillRegistryService;
import com.mai.deerflow.backend.runtime.state.RunStateMachine;
import com.mai.deerflow.backend.runtime.subtask.SubTaskExecutor;
import com.mai.deerflow.backend.runtime.upload.DocumentMarkdownConversionService;
import com.mai.deerflow.backend.runtime.upload.UploadService;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceProperties;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThreadRuntimeServiceRunOptionsTests {

    @TempDir
    Path tempDir;

    @Test
    void shouldPassRequestedModelNameToPromptOptions() {
        ObservingChatModel chatModel = new ObservingChatModel();
        ThreadRuntimeService service = fixture(chatModel);
        service.setModelRegistryService(modelRegistryService(List.of(
                new ModelDescriptor("runtime-model-b", "test", true, List.of("chat"), "test model")
        )));

        String threadId = "run-options-model-" + UUID.randomUUID();
        try {
            service.createThread(threadId);
            ThreadStateSnapshot snapshot = service.runThread(
                    threadId,
                    "use requested model",
                    false,
                    null,
                    null,
                    new RequestedRuntimeRunOptions("runtime-model-b", null, null, null, null, null)
            );

            assertThat(snapshot.runStatus().name()).isEqualTo("COMPLETED");
            assertThat(chatModel.lastModelName()).isEqualTo("runtime-model-b");
        }
        finally {
            service.deleteThread(threadId);
        }
    }

    @Test
    void shouldPersistRunOptionsAcrossApprovalResume() {
        ObservingChatModel chatModel = new ObservingChatModel();
        ThreadRuntimeService service = fixture(chatModel);
        service.setModelRegistryService(modelRegistryService(List.of(
                new ModelDescriptor("runtime-model-c", "test", true, List.of("chat"), "test model")
        )));

        String threadId = "run-options-resume-" + UUID.randomUUID();
        try {
            service.createThread(threadId);
            ThreadStateSnapshot waitingSnapshot = service.runThread(
                    threadId,
                    "resume with requested model",
                    true,
                    "Need approval first",
                    null,
                    new RequestedRuntimeRunOptions("runtime-model-c", null, null, null, null, null)
            );

            service.submitApproval(
                    threadId,
                    waitingSnapshot.approval().approvalId(),
                    new ApprovalSubmissionRequest(ApprovalDecision.APPROVE, "approved")
            );
            service.resumeThread(threadId, new ResumeThreadRequest("resume now"));

            assertThat(chatModel.lastModelName()).isEqualTo("runtime-model-c");
        }
        finally {
            service.deleteThread(threadId);
        }
    }

    @Test
    void shouldDisablePlanModeAndSubagentToolInLeadAgentAssembly() throws Exception {
        ThreadRuntimeService service = fixture(new ObservingChatModel());
        Method runtimeLeadAgentMethod = ThreadRuntimeService.class.getDeclaredMethod(
                "runtimeLeadAgent",
                String.class,
                String.class,
                RuntimeRunOptions.class
        );
        runtimeLeadAgentMethod.setAccessible(true);

        ReactAgent agent = (ReactAgent) runtimeLeadAgentMethod.invoke(
                service,
                "assembly-thread",
                "assembly-run",
                new RuntimeRunOptions(null, false, false, 2)
        );

        Field toolNodeField = ReactAgent.class.getDeclaredField("toolNode");
        toolNodeField.setAccessible(true);
        Object toolNode = toolNodeField.get(agent);
        @SuppressWarnings("unchecked")
        List<ToolCallback> toolCallbacks = (List<ToolCallback>) toolNode.getClass().getMethod("getToolCallbacks").invoke(toolNode);

        Field modelInterceptorsField = ReactAgent.class.getDeclaredField("modelInterceptors");
        modelInterceptorsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Object> modelInterceptors = (List<Object>) modelInterceptorsField.get(agent);

        assertThat(toolCallbacks.stream().map(tool -> tool.getToolDefinition().name()).toList())
                .doesNotContain("task");
        assertThat(modelInterceptors.stream().map(interceptor -> interceptor.getClass().getName()).toList())
                .noneMatch(name -> name.contains("TodoListInterceptor"))
                .noneMatch(name -> name.endsWith("RuntimeTodoReminderInterceptor"));
    }

    @Test
    void shouldRejectUnsupportedReasoningEffortOption() {
        ThreadRuntimeService service = fixture(new ObservingChatModel());

        assertThatThrownBy(() -> service.runThread(
                "unsupported-option-thread",
                "test unsupported option",
                false,
                null,
                null,
                new RequestedRuntimeRunOptions(null, "high", null, null, null, null)
        ))
                .isInstanceOf(InvalidRunOptionException.class)
                .hasMessageContaining("reasoningEffort");
    }

    private ThreadRuntimeService fixture(ChatModel chatModel) {
        ThreadWorkspaceProperties workspaceProperties = new ThreadWorkspaceProperties();
        workspaceProperties.setBaseDir(tempDir.resolve("threads"));
        ThreadWorkspaceService threadWorkspaceService = new ThreadWorkspaceService(workspaceProperties);
        DocumentMarkdownConversionService documentMarkdownConversionService = new DocumentMarkdownConversionService();
        UploadService uploadService = new UploadService(threadWorkspaceService, documentMarkdownConversionService);
        ArtifactService artifactService = new ArtifactService(threadWorkspaceService);

        RuntimeConfigProperties runtimeConfigProperties = new RuntimeConfigProperties();
        runtimeConfigProperties.setFile(tempDir.resolve("runtime-config.json"));
        FileRuntimeConfigRepository repository = new FileRuntimeConfigRepository(runtimeConfigProperties, new ObjectMapper());
        SkillRegistryService skillRegistryService = new SkillRegistryService(repository);

        MemoryStoreProperties memoryStoreProperties = new MemoryStoreProperties();
        memoryStoreProperties.setBaseDir(tempDir.resolve("memory"));
        FileMemoryStore memoryStore = new FileMemoryStore(memoryStoreProperties, new ObjectMapper());
        MemoryInjectionService memoryInjectionService = new MemoryInjectionService(memoryStore, new MemoryInjectionProperties());

        LeadAgentFactory leadAgentFactory = new LeadAgentFactory();
        RuntimeAgentEnhancementService runtimeAgentEnhancementService = new RuntimeAgentEnhancementService(new ObjectMapper());
        RuntimeLeadAgentPromptService runtimeLeadAgentPromptService = new RuntimeLeadAgentPromptService(
                threadWorkspaceService,
                uploadService,
                skillRegistryService
        );

        RuntimeCheckpointProperties runtimeCheckpointProperties = new RuntimeCheckpointProperties();
        runtimeCheckpointProperties.setBaseDir(tempDir.resolve("checkpoints"));
        RuntimeCheckpointService runtimeCheckpointService = new RuntimeCheckpointService(runtimeCheckpointProperties);
        ThreadEventService threadEventService = new ThreadEventService();
        MemoryExtractorJob memoryExtractorJob = new MemoryExtractorJob(memoryStore, Runnable::run);
        SubTaskExecutor subTaskExecutor = new SubTaskExecutor(
                threadWorkspaceService,
                leadAgentFactory,
                chatModel,
                threadEventService,
                new ObjectMapper(),
                Runnable::run
        );

        return new ThreadRuntimeService(
                threadWorkspaceService,
                leadAgentFactory,
                runtimeAgentEnhancementService,
                runtimeLeadAgentPromptService,
                memoryInjectionService,
                chatModel,
                new RunStateMachine(),
                new ObjectMapper(),
                uploadService,
                artifactService,
                threadEventService,
                memoryExtractorJob,
                subTaskExecutor,
                new PostRunGenerationService(),
                runtimeCheckpointService
        );
    }

    private ModelRegistryService modelRegistryService(List<ModelDescriptor> descriptors) {
        RuntimeConfigProperties runtimeConfigProperties = new RuntimeConfigProperties();
        runtimeConfigProperties.setFile(tempDir.resolve("model-registry-config.json"));
        FileRuntimeConfigRepository repository = new FileRuntimeConfigRepository(runtimeConfigProperties, new ObjectMapper());
        ModelRegistryService service = new ModelRegistryService(repository);
        service.replaceModels(descriptors);
        return service;
    }

    private static final class ObservingChatModel implements ChatModel {

        private final AtomicReference<String> lastModelName = new AtomicReference<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            ChatOptions options = prompt.getOptions();
            lastModelName.set(options == null ? null : options.getModel());

            String lastUserMessage = prompt.getInstructions().stream()
                    .filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast)
                    .map(UserMessage::getText)
                    .reduce((previous, current) -> current)
                    .orElse("");

            return new ChatResponse(List.of(new Generation(new AssistantMessage("Processed: " + lastUserMessage))));
        }

        String lastModelName() {
            return lastModelName.get();
        }
    }
}
