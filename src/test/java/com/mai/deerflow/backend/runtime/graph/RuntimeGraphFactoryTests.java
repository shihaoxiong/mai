package com.mai.deerflow.backend.runtime.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.mai.deerflow.backend.runtime.contract.ArtifactRef;
import com.mai.deerflow.backend.runtime.upload.DocumentMarkdownConversionService;
import com.mai.deerflow.backend.runtime.upload.UploadService;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceProperties;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceService;
import com.mai.deerflow.backend.runtime.workspace.WorkspaceArea;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeGraphFactoryTests {

    @TempDir
    Path tempDir;

    private ThreadWorkspaceService threadWorkspaceService;
    private RuntimeGraphFactory runtimeGraphFactory;

    @BeforeEach
    void setUp() {
        ThreadWorkspaceProperties properties = new ThreadWorkspaceProperties();
        properties.setBaseDir(tempDir.resolve("threads"));
        threadWorkspaceService = new ThreadWorkspaceService(properties);
        runtimeGraphFactory = new RuntimeGraphFactory(
                threadWorkspaceService,
                new UploadService(threadWorkspaceService, new DocumentMarkdownConversionService())
        );
    }

    @Test
    void shouldExecuteMinimalRuntimeFlowAndPersistArtifacts() throws Exception {
        CompiledGraph compiledGraph = runtimeGraphFactory.create(stubLeadAgentNode(), new MemorySaver());

        Optional<OverAllState> result = compiledGraph.invoke(
                Map.of(RuntimeStateKeys.USER_INPUT, "summarize the uploaded brief"),
                RunnableConfig.builder().threadId("thread-runtime").build()
        );

        assertThat(result).isPresent();
        OverAllState state = result.orElseThrow();

        assertThat(state.value(RuntimeStateKeys.THREAD_ID, String.class)).contains("thread-runtime");
        assertThat(state.value(RuntimeStateKeys.RUN_STATUS)
                .map(Object::toString)
                .orElse(""))
                .contains("COMPLETED");
        assertThat(state.value(RuntimeStateKeys.CONTEXT_READY, Boolean.class)).contains(true);
        assertThat(extractOutputsPath(state.value(RuntimeStateKeys.WORKSPACE).orElseThrow()))
                .isEqualTo(tempDir.resolve("threads/thread-runtime/outputs").toString());
        assertThat(state.value(RuntimeStateKeys.TITLE, String.class)).contains("Runtime Graph Demo");
        assertThat(state.value(RuntimeStateKeys.SUGGESTIONS, List.class)).contains(List.of("review generated summary"));
        assertThat(state.value(RuntimeStateKeys.ARTIFACTS, List.class)).hasValueSatisfying(artifacts -> {
            List<?> refs = artifacts;
            assertThat(refs).hasSize(1);
            assertThat(extractArtifactName(refs.get(0))).isEqualTo("summary.md");
            assertThat(extractArtifactVirtualPath(refs.get(0))).isEqualTo("/outputs/summary.md");
        });
    }

    @Test
    void shouldExposeExpectedGraphShape() {
        CompiledGraph compiledGraph = runtimeGraphFactory.create(stubLeadAgentNode());

        String graph = compiledGraph.getGraph(com.alibaba.cloud.ai.graph.GraphRepresentation.Type.PLANTUML)
                .content();

        assertThat(graph)
                .contains(RuntimeGraphFactory.PREPARE_THREAD_NODE)
                .contains(RuntimeGraphFactory.ASSEMBLE_CONTEXT_NODE)
                .contains(RuntimeGraphFactory.RUN_LEAD_AGENT_NODE)
                .contains(RuntimeGraphFactory.PERSIST_ARTIFACTS_NODE);
    }

    private AsyncNodeActionWithConfig stubLeadAgentNode() {
        return (state, config) -> {
            try {
                Path outputFile = threadWorkspaceService.resolveRelativePath(
                        config.threadId().orElseThrow(),
                        WorkspaceArea.OUTPUTS,
                        "summary.md"
                );
                Files.writeString(outputFile, "# Runtime Graph Demo");

                return CompletableFuture.completedFuture(Map.of(
                        RuntimeStateKeys.TITLE, "Runtime Graph Demo",
                        RuntimeStateKeys.SUGGESTIONS, List.of("review generated summary")
                ));
            }
            catch (Exception exception) {
                CompletableFuture<Map<String, Object>> failed = new CompletableFuture<>();
                failed.completeExceptionally(exception);
                return failed;
            }
        };
    }

    private String extractOutputsPath(Object workspace) {
        if (workspace instanceof Map<?, ?> workspaceMap) {
            return String.valueOf(workspaceMap.get("outputsPath"));
        }
        return ((com.mai.deerflow.backend.runtime.contract.WorkspaceState) workspace).outputsPath();
    }

    private String extractArtifactName(Object artifact) {
        if (artifact instanceof Map<?, ?> artifactMap) {
            return String.valueOf(artifactMap.get("name"));
        }
        return ((ArtifactRef) artifact).name();
    }

    private String extractArtifactVirtualPath(Object artifact) {
        if (artifact instanceof Map<?, ?> artifactMap) {
            return String.valueOf(artifactMap.get("virtualPath"));
        }
        return ((ArtifactRef) artifact).virtualPath();
    }
}
