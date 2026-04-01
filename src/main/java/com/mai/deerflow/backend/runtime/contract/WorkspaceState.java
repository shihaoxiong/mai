package com.mai.deerflow.backend.runtime.contract;

/**
 * 线程工作区三类根目录的绝对路径视图。
 */
public record WorkspaceState(
        String workspacePath,
        String uploadsPath,
        String outputsPath
) {
}
