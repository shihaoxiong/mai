package com.mai.deerflow.backend.runtime.checkpoint;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

@ConfigurationProperties(prefix = "mai.runtime.checkpoint")
/**
 * runtime checkpoint 的目录配置。
 *
 * 当前把 outer runtime graph 和 lead agent 的 checkpoint
 * 都放在独立目录中，避免不同运行时层级互相污染。
 */
public class RuntimeCheckpointProperties {

    private Path baseDir = Paths.get("data", "checkpoints");

    public Path getBaseDir() {
        return baseDir;
    }

    public void setBaseDir(Path baseDir) {
        this.baseDir = baseDir;
    }
}
