package com.mai.deerflow.backend.runtime.checkpoint;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.file.FileSystemSaver;

import java.nio.file.Path;

/**
 * 在官方 `FileSystemSaver` 基础上补一个线程级清理能力。
 *
 * 这样删除线程时可以同时清掉：
 * 1. 磁盘上的 checkpoint 文件
 * 2. saver 内部的内存缓存
 */
public class ResettableFileSystemSaver extends FileSystemSaver {

    public ResettableFileSystemSaver(Path targetFolder) {
        super(targetFolder, null);
    }

    /**
     * 清除某个线程的 checkpoint 文件与内存缓存。
     */
    public void purgeThread(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return;
        }
        remove(threadId);
        deleteFile(RunnableConfig.builder().threadId(threadId.trim()).build());
    }
}
