package com.mai.deerflow.backend.runtime.contract;

import java.util.List;

public record ThreadStateSnapshot(
        String threadId,
        String runId,
        RunStatus runStatus,
        WorkspaceState workspace,
        List<UploadRef> uploads,
        List<ArtifactRef> artifacts,
        List<TodoItem> todos,
        ApprovalState approval,
        List<String> suggestions,
        String title
) {
}
