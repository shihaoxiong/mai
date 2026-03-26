package com.mai.deerflow.backend.runtime.graph;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.mai.deerflow.backend.runtime.artifact.ArtifactService;
import com.mai.deerflow.backend.runtime.contract.ArtifactRef;
import com.mai.deerflow.backend.runtime.contract.RunStatus;
import com.mai.deerflow.backend.runtime.contract.UploadRef;
import com.mai.deerflow.backend.runtime.memory.MemoryInjectionResult;
import com.mai.deerflow.backend.runtime.memory.MemoryInjectionService;
import com.mai.deerflow.backend.runtime.upload.UploadService;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspace;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
/**
 * 统一构造 runtime 主流程图。
 *
 * 当前图骨架固定为：
 * `PrepareThread -> AssembleContext -> RunLeadAgent -> PersistArtifacts`
 */
public class RuntimeGraphFactory {

    public static final String PREPARE_THREAD_NODE = "prepareThread";
    public static final String ASSEMBLE_CONTEXT_NODE = "assembleContext";
    public static final String RUN_LEAD_AGENT_NODE = "runLeadAgent";
    public static final String PERSIST_ARTIFACTS_NODE = "persistArtifacts";

    private final ThreadWorkspaceService threadWorkspaceService;
    private final UploadService uploadService;
    private final ArtifactService artifactService;
    private final MemoryInjectionService memoryInjectionService;

    public RuntimeGraphFactory(ThreadWorkspaceService threadWorkspaceService,
                               UploadService uploadService,
                               ArtifactService artifactService,
                               MemoryInjectionService memoryInjectionService) {
        this.threadWorkspaceService = threadWorkspaceService;
        this.uploadService = uploadService;
        this.artifactService = artifactService;
        this.memoryInjectionService = memoryInjectionService;
    }

    /**
     * 使用默认编译配置创建 runtime graph。
     */
    public CompiledGraph create(AsyncNodeActionWithConfig runLeadAgentNode) {
        return create(runLeadAgentNode, null);
    }

    /**
     * 使用指定 checkpoint saver 创建 runtime graph。
     */
    public CompiledGraph create(AsyncNodeActionWithConfig runLeadAgentNode, BaseCheckpointSaver checkpointSaver) {
        try {
            StateGraph stateGraph = new StateGraph();
            stateGraph.addNode(PREPARE_THREAD_NODE, this::prepareThreadNode);
            stateGraph.addNode(ASSEMBLE_CONTEXT_NODE, this::assembleContextNode);
            stateGraph.addNode(RUN_LEAD_AGENT_NODE, runLeadAgentNode);
            stateGraph.addNode(PERSIST_ARTIFACTS_NODE, this::persistArtifactsNode);

            stateGraph.addEdge(StateGraph.START, PREPARE_THREAD_NODE);
            stateGraph.addEdge(PREPARE_THREAD_NODE, ASSEMBLE_CONTEXT_NODE);
            stateGraph.addEdge(ASSEMBLE_CONTEXT_NODE, RUN_LEAD_AGENT_NODE);
            stateGraph.addEdge(RUN_LEAD_AGENT_NODE, PERSIST_ARTIFACTS_NODE);
            stateGraph.addEdge(PERSIST_ARTIFACTS_NODE, StateGraph.END);

            return stateGraph.compile(compileConfig(checkpointSaver));
        }
        catch (Exception exception) {
            throw new IllegalStateException("Failed to create runtime graph", exception);
        }
    }

    private CompileConfig compileConfig(BaseCheckpointSaver checkpointSaver) {
        CompileConfig.Builder builder = CompileConfig.builder();
        if (checkpointSaver != null) {
            builder.saverConfig(SaverConfig.builder().register(checkpointSaver).build());
        }
        return builder.build();
    }

    private CompletableFuture<Map<String, Object>> prepareThreadNode(OverAllState state, RunnableConfig config) {
        String threadId = resolveThreadId(state, config);
        ThreadWorkspace threadWorkspace = threadWorkspaceService.getOrCreateWorkspace(threadId);
        String runId = state.value(RuntimeStateKeys.RUN_ID, threadId + "-run");

        return CompletableFuture.completedFuture(Map.of(
                RuntimeStateKeys.THREAD_ID, threadId,
                RuntimeStateKeys.RUN_ID, runId,
                RuntimeStateKeys.WORKSPACE, threadWorkspace.toState(),
                RuntimeStateKeys.RUN_STATUS, RunStatus.RUNNING
        ));
    }

    /**
     * 汇总上传文件和基础上下文，供后续 lead agent 使用。
     */
    private CompletableFuture<Map<String, Object>> assembleContextNode(OverAllState state, RunnableConfig config) {
        String userInput = state.value(RuntimeStateKeys.USER_INPUT, "");
        String threadId = resolveThreadId(state, config);
        String userId = state.value(RuntimeStateKeys.USER_ID, String.class).orElse(null);
        List<UploadRef> uploads = uploadService.listUploads(threadId);
        MemoryInjectionResult memoryInjectionResult = memoryInjectionService.inject(userId, userInput);
        return CompletableFuture.completedFuture(Map.of(
                RuntimeStateKeys.CONTEXT_READY, true,
                RuntimeStateKeys.USER_INPUT, userInput,
                RuntimeStateKeys.AGENT_INPUT, memoryInjectionResult.effectiveUserInput(),
                RuntimeStateKeys.UPLOADS, uploads,
                RuntimeStateKeys.MEMORY_CONTEXT, memoryInjectionResult.memoryFacts()
        ));
    }

    /**
     * 在图尾部重新扫描 outputs 目录，生成最新产物元数据。
     */
    private CompletableFuture<Map<String, Object>> persistArtifactsNode(OverAllState state, RunnableConfig config) {
        String threadId = resolveThreadId(state, config);
        List<ArtifactRef> artifacts = artifactService.listArtifacts(threadId);

        return CompletableFuture.completedFuture(Map.of(
                RuntimeStateKeys.ARTIFACTS, artifacts,
                RuntimeStateKeys.RUN_STATUS, RunStatus.COMPLETED
        ));
    }

    private String resolveThreadId(OverAllState state, RunnableConfig config) {
        return config.threadId()
                .or(() -> state.value(RuntimeStateKeys.THREAD_ID, String.class))
                .orElseThrow(() -> new IllegalArgumentException("threadId is required in runnable config or state"));
    }
}
