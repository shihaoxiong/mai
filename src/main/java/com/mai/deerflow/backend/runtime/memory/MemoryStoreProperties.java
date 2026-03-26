package com.mai.deerflow.backend.runtime.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

@ConfigurationProperties(prefix = "mai.memory")
/**
 * 长期记忆默认存储实现的目录配置。
 */
public class MemoryStoreProperties {

    private Path baseDir = Paths.get("data", "memory");

    public Path getBaseDir() {
        return baseDir;
    }

    public void setBaseDir(Path baseDir) {
        this.baseDir = baseDir;
    }
}
