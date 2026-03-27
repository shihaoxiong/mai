package com.mai.deerflow.backend.runtime.checkpoint;

import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
/**
 * 统一管理 runtime 相关 checkpoint saver。
 *
 * 当前拆成两套持久化空间：
 * 1. outer runtime graph
 * 2. lead agent 会话短期记忆
 */
public class RuntimeCheckpointService {

    private final ResettableFileSystemSaver runtimeGraphSaver;
    private final ResettableFileSystemSaver leadAgentSaver;

    public RuntimeCheckpointService(RuntimeCheckpointProperties properties) {
        Path baseDir = properties.getBaseDir().toAbsolutePath().normalize();
        this.runtimeGraphSaver = new ResettableFileSystemSaver(baseDir.resolve("runtime-graph"));
        this.leadAgentSaver = new ResettableFileSystemSaver(baseDir.resolve("lead-agent"));
    }

    public ResettableFileSystemSaver runtimeGraphSaver() {
        return runtimeGraphSaver;
    }

    public ResettableFileSystemSaver leadAgentSaver() {
        return leadAgentSaver;
    }

    /**
     * 删除线程时同步清理 runtime checkpoint，避免同名线程拿到旧状态。
     */
    public void deleteThreadCheckpoints(String threadId) {
        runtimeGraphSaver.purgeThread(threadId);
        leadAgentSaver.purgeThread(threadId);
    }
}
