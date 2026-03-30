package com.mai.deerflow.backend.runtime.checkpoint;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

@ConfigurationProperties(prefix = "mai.runtime.checkpoint")
/**
 * runtime checkpoint 的目录配置。
 *
 * 当前主链路统一使用 lead agent checkpoint，
 * 目录根位置仍保持可配置，便于本地开发和后续替换存储实现。
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
