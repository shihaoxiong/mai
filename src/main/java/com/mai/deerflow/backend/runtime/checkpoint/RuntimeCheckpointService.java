package com.mai.deerflow.backend.runtime.checkpoint;

import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
/**
 * 统一管理 runtime 相关 checkpoint saver。
 *
 * 当前主链路统一使用 lead agent saver。
 */
public class RuntimeCheckpointService {

    private final ResettableFileSystemSaver leadAgentSaver;

    public RuntimeCheckpointService(RuntimeCheckpointProperties properties) {
        Path baseDir = properties.getBaseDir().toAbsolutePath().normalize();
        this.leadAgentSaver = new ResettableFileSystemSaver(baseDir.resolve("lead-agent"));
    }

    public ResettableFileSystemSaver leadAgentSaver() {
        return leadAgentSaver;
    }

    /**
     * 删除线程时同步清理 lead agent checkpoint，避免同名线程拿到旧状态。
     */
    public void deleteThreadCheckpoints(String threadId) {
        leadAgentSaver.purgeThread(threadId);
    }
}
