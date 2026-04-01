package com.mai.deerflow.backend.runtime.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThreadWorkspaceServiceTests {

    @TempDir
    Path tempDir;

    private ThreadWorkspaceService threadWorkspaceService;

    @BeforeEach
    void setUp() {
        ThreadWorkspaceProperties properties = new ThreadWorkspaceProperties();
        properties.setBaseDir(tempDir.resolve("threads"));
        threadWorkspaceService = new ThreadWorkspaceService(properties);
    }

    @Test
    void createWorkspaceShouldCreateExpectedDirectoryLayout() {
        ThreadWorkspace workspace = threadWorkspaceService.createWorkspace("thread-001");

        assertThat(workspace.threadId()).isEqualTo("thread-001");
        assertThat(Files.isDirectory(workspace.threadRoot())).isTrue();
        assertThat(Files.isDirectory(workspace.workspaceRoot())).isTrue();
        assertThat(Files.isDirectory(workspace.uploadsRoot())).isTrue();
        assertThat(Files.isDirectory(workspace.outputsRoot())).isTrue();
    }

    @Test
    void resolveVirtualPathShouldMapToThreadScopedDirectories() throws Exception {
        threadWorkspaceService.createWorkspace("thread-002");
        Path resolved = threadWorkspaceService.resolveVirtualPath("thread-002", "/uploads/brief.md");

        Files.createDirectories(resolved.getParent());
        Files.writeString(resolved, "hello");

        assertThat(resolved.toString().replace('\\', '/')).endsWith("threads/thread-002/uploads/brief.md");
        assertThat(threadWorkspaceService.toVirtualPath("thread-002", resolved)).isEqualTo("/uploads/brief.md");
    }

    @Test
    void resolveVirtualPathShouldRejectTraversal() {
        threadWorkspaceService.createWorkspace("thread-003");

        assertThatThrownBy(() -> threadWorkspaceService.resolveVirtualPath("thread-003", "/uploads/../../etc/passwd"))
                .isInstanceOf(InvalidWorkspacePathException.class)
                .hasMessageContaining("escapes");
    }

    @Test
    void resolveVirtualPathShouldRejectUnknownRoots() {
        threadWorkspaceService.createWorkspace("thread-004");

        assertThatThrownBy(() -> threadWorkspaceService.resolveVirtualPath("thread-004", "/tmp/demo.txt"))
                .isInstanceOf(InvalidWorkspacePathException.class)
                .hasMessageContaining("Unsupported virtual root");
    }

    @Test
    void deleteWorkspaceShouldRemoveThreadDirectoryRecursively() throws Exception {
        ThreadWorkspace workspace = threadWorkspaceService.createWorkspace("thread-005");
        Path artifact = workspace.outputsRoot().resolve("result.txt");
        Files.writeString(artifact, "artifact");

        threadWorkspaceService.deleteWorkspace("thread-005");

        assertThat(Files.exists(workspace.threadRoot())).isFalse();
    }

    @Test
    void threadIdShouldRejectPathSeparators() {
        assertThatThrownBy(() -> threadWorkspaceService.createWorkspace("../bad-thread"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("illegal path characters");
    }
}
