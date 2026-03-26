package com.mai.deerflow.backend.runtime.workspace;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

@ConfigurationProperties(prefix = "mai.workspace")
/**
 * 线程工作区的基础配置。
 *
 * 当前只开放根目录位置，便于本地开发、测试环境和后续对象存储挂载场景切换。
 */
public class ThreadWorkspaceProperties {

    private Path baseDir = Paths.get("data", "threads");

    public Path getBaseDir() {
        return baseDir;
    }

    public void setBaseDir(Path baseDir) {
        this.baseDir = baseDir;
    }
}
