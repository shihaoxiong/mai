package com.mai.deerflow.backend.runtime.contract;

import java.util.List;

/**
 * 线程在平台层暴露给前端与恢复逻辑的聚合状态快照。
 */
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
