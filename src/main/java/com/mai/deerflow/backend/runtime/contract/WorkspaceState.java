package com.mai.deerflow.backend.runtime.contract;

public record WorkspaceState(
        String workspacePath,
        String uploadsPath,
        String outputsPath
) {
}
