package com.mai.deerflow.backend.runtime.sandbox;

import com.mai.deerflow.backend.runtime.workspace.InvalidWorkspacePathException;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceProperties;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceService;
import com.mai.deerflow.backend.runtime.workspace.WorkspaceArea;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalProcessSandboxProviderTests {

    @TempDir
    Path tempDir;

    private LocalProcessSandboxProvider sandboxProvider;

    @BeforeEach
    void setUp() {
        ThreadWorkspaceProperties properties = new ThreadWorkspaceProperties();
        properties.setBaseDir(tempDir.resolve("threads"));
        ThreadWorkspaceService threadWorkspaceService = new ThreadWorkspaceService(properties);
        threadWorkspaceService.createWorkspace("sandbox-thread");
        sandboxProvider = new LocalProcessSandboxProvider(threadWorkspaceService);
    }

    @Test
    void shouldExecuteCommandInsideThreadWorkspace() {
        CommandExecutionResult result = sandboxProvider.execute(new CommandExecutionRequest(
                "sandbox-thread",
                WorkspaceArea.WORKSPACE,
                "",
                List.of("/bin/sh", "-lc", "printf 'sandbox-ok'"),
                Duration.ofSeconds(5)
        ));

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.stdout()).isEqualTo("sandbox-ok");
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    void shouldWriteReadListAndReplaceFiles() {
        sandboxProvider.writeFile("sandbox-thread", WorkspaceArea.UPLOADS, "docs/brief.txt", "hello deerflow");

        assertThat(sandboxProvider.listDirectory("sandbox-thread", WorkspaceArea.UPLOADS, "docs"))
                .containsExactly("brief.txt");
        assertThat(sandboxProvider.readFile("sandbox-thread", WorkspaceArea.UPLOADS, "docs/brief.txt"))
                .isEqualTo("hello deerflow");

        sandboxProvider.replaceInFile("sandbox-thread", WorkspaceArea.UPLOADS, "docs/brief.txt", "deerflow", "spring-ai");

        assertThat(sandboxProvider.readFile("sandbox-thread", WorkspaceArea.UPLOADS, "docs/brief.txt"))
                .isEqualTo("hello spring-ai");
    }

    @Test
    void shouldFailWhenReplacingMissingContent() {
        sandboxProvider.writeFile("sandbox-thread", WorkspaceArea.OUTPUTS, "result.txt", "done");

        assertThatThrownBy(() -> sandboxProvider.replaceInFile(
                "sandbox-thread",
                WorkspaceArea.OUTPUTS,
                "result.txt",
                "missing",
                "found"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target text not found");
    }

    @Test
    void shouldRejectEscapingWorkingDirectoryForCommandExecution() {
        assertThatThrownBy(() -> sandboxProvider.execute(new CommandExecutionRequest(
                "sandbox-thread",
                WorkspaceArea.WORKSPACE,
                "../outside",
                List.of("/bin/sh", "-lc", "pwd"),
                Duration.ofSeconds(5)
        ))).isInstanceOf(InvalidWorkspacePathException.class)
                .hasMessageContaining("escapes");
    }

    @Test
    void shouldRejectEscapingFileAccess() {
        assertThatThrownBy(() -> sandboxProvider.readFile(
                "sandbox-thread",
                WorkspaceArea.UPLOADS,
                "../workspace/secret.txt"
        )).isInstanceOf(InvalidWorkspacePathException.class)
                .hasMessageContaining("escapes");
    }
}
