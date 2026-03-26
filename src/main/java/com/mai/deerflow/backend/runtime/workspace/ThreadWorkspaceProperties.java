package com.mai.deerflow.backend.runtime.workspace;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

@ConfigurationProperties(prefix = "mai.workspace")
public class ThreadWorkspaceProperties {

    private Path baseDir = Paths.get("data", "threads");

    public Path getBaseDir() {
        return baseDir;
    }

    public void setBaseDir(Path baseDir) {
        this.baseDir = baseDir;
    }
}
