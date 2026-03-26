package com.mai.deerflow.backend.runtime.sandbox;

import com.mai.deerflow.backend.runtime.workspace.WorkspaceArea;

import java.time.Duration;
import java.util.List;

public record CommandExecutionRequest(
        String threadId,
        WorkspaceArea area,
        String workingDirectory,
        List<String> command,
        Duration timeout
) {
}
