package com.mai.deerflow.backend.runtime.workspace;

import com.mai.deerflow.backend.runtime.contract.WorkspaceState;

import java.nio.file.Path;

public record ThreadWorkspace(
        String threadId,
        Path threadRoot,
        Path workspaceRoot,
        Path uploadsRoot,
        Path outputsRoot
) {

    public Path rootFor(WorkspaceArea area) {
        return switch (area) {
            case WORKSPACE -> workspaceRoot;
            case UPLOADS -> uploadsRoot;
            case OUTPUTS -> outputsRoot;
        };
    }

    public WorkspaceState toState() {
        return new WorkspaceState(
                workspaceRoot.toString(),
                uploadsRoot.toString(),
                outputsRoot.toString()
        );
    }
}
