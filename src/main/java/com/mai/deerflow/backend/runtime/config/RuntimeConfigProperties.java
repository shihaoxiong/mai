package com.mai.deerflow.backend.runtime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

@ConfigurationProperties(prefix = "mai.runtime-config")
public class RuntimeConfigProperties {

    private Path file = Paths.get("data", "runtime-config", "runtime-config.json");

    public Path getFile() {
        return file;
    }

    public void setFile(Path file) {
        this.file = file;
    }
}
