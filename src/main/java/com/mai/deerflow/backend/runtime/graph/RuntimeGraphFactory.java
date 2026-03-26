package com.mai.deerflow.backend.runtime.graph;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.mai.deerflow.backend.runtime.contract.ArtifactRef;
import com.mai.deerflow.backend.runtime.contract.RunStatus;
import com.mai.deerflow.backend.runtime.contract.UploadRef;
import com.mai.deerflow.backend.runtime.contract.WorkspaceState;
import com.mai.deerflow.backend.runtime.upload.UploadService;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspace;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceService;
import com.mai.deerflow.backend.runtime.workspace.WorkspaceArea;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

@Component
public class RuntimeGraphFactory {

    public static final String PREPARE_THREAD_NODE = "prepareThread";
    public static final String ASSEMBLE_CONTEXT_NODE = "assembleContext";
    public static final String RUN_LEAD_AGENT_NODE = "runLeadAgent";
    public static final String PERSIST_ARTIFACTS_NODE = "persistArtifacts";

    private final ThreadWorkspaceService threadWorkspaceService;
    private final UploadService uploadService;

    public RuntimeGraphFactory(ThreadWorkspaceService threadWorkspaceService, UploadService uploadService) {
        this.threadWorkspaceService = threadWorkspaceService;
        this.uploadService = uploadService;
    }

    public CompiledGraph create(AsyncNodeActionWithConfig runLeadAgentNode) {
        return create(runLeadAgentNode, null);
    }

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

    private CompletableFuture<Map<String, Object>> assembleContextNode(OverAllState state, RunnableConfig config) {
        String userInput = state.value(RuntimeStateKeys.USER_INPUT, "");
        String threadId = resolveThreadId(state, config);
        List<UploadRef> uploads = uploadService.listUploads(threadId);
        return CompletableFuture.completedFuture(Map.of(
                RuntimeStateKeys.CONTEXT_READY, true,
                RuntimeStateKeys.USER_INPUT, userInput,
                RuntimeStateKeys.UPLOADS, uploads
        ));
    }

    private CompletableFuture<Map<String, Object>> persistArtifactsNode(OverAllState state, RunnableConfig config) {
        String threadId = resolveThreadId(state, config);
        WorkspaceState workspaceState = state.value(RuntimeStateKeys.WORKSPACE, WorkspaceState.class)
                .orElseThrow(() -> new IllegalStateException("workspace state is missing"));

        List<ArtifactRef> artifacts = collectArtifacts(threadId, Path.of(workspaceState.outputsPath()));

        return CompletableFuture.completedFuture(Map.of(
                RuntimeStateKeys.ARTIFACTS, artifacts,
                RuntimeStateKeys.RUN_STATUS, RunStatus.COMPLETED
        ));
    }

    private List<ArtifactRef> collectArtifacts(String threadId, Path outputsRoot) {
        if (!Files.isDirectory(outputsRoot)) {
            return List.of();
        }

        try (Stream<Path> paths = Files.walk(outputsRoot)) {
            return paths.filter(Files::isRegularFile)
                    .sorted(Comparator.naturalOrder())
                    .map(path -> new ArtifactRef(
                            path.getFileName().toString(),
                            threadWorkspaceService.toVirtualPath(threadId, path),
                            Optional.ofNullable(probeContentType(path)).orElse("application/octet-stream")
                    ))
                    .toList();
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to collect artifacts from " + outputsRoot, exception);
        }
    }

    private String probeContentType(Path path) {
        try {
            return Files.probeContentType(path);
        }
        catch (IOException exception) {
            return null;
        }
    }

    private String resolveThreadId(OverAllState state, RunnableConfig config) {
        return config.threadId()
                .or(() -> state.value(RuntimeStateKeys.THREAD_ID, String.class))
                .orElseThrow(() -> new IllegalArgumentException("threadId is required in runnable config or state"));
    }
}
