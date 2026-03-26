package com.mai.deerflow.backend.runtime.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mai.memory.injection")
/**
 * 长期记忆注入策略的基础配置。
 */
public class MemoryInjectionProperties {

    private boolean enabled = true;
    private int maxFacts = 3;
    private double minConfidence = 0.7d;
    private String strategy = "append-to-user-input";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxFacts() {
        return maxFacts;
    }

    public void setMaxFacts(int maxFacts) {
        this.maxFacts = maxFacts;
    }

    public double getMinConfidence() {
        return minConfidence;
    }

    public void setMinConfidence(double minConfidence) {
        this.minConfidence = minConfidence;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }
}
