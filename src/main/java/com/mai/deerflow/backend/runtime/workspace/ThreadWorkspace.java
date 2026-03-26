package com.mai.deerflow.backend.runtime.workspace;

import com.mai.deerflow.backend.runtime.contract.WorkspaceState;

import java.nio.file.Path;

/**
 * 线程工作区在本地文件系统中的实际目录映射。
 */
public record ThreadWorkspace(
        String threadId,
        Path threadRoot,
        Path workspaceRoot,
        Path uploadsRoot,
        Path outputsRoot
) {

    /**
     * 根据区域类型返回对应的根目录。
     */
    public Path rootFor(WorkspaceArea area) {
        return switch (area) {
            case WORKSPACE -> workspaceRoot;
            case UPLOADS -> uploadsRoot;
            case OUTPUTS -> outputsRoot;
        };
    }

    /**
     * 转换为适合对外传输的只读状态对象。
     */
    public WorkspaceState toState() {
        return new WorkspaceState(
                workspaceRoot.toString(),
                uploadsRoot.toString(),
                outputsRoot.toString()
        );
    }
}
