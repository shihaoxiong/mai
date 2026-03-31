package com.mai.deerflow.backend.runtime.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 结构化长期记忆更新队列的基础配置。
 */
@ConfigurationProperties(prefix = "mai.memory.queue")
public class MemoryUpdateQueueProperties {

    private long debounceMillis = 300L;

    public long getDebounceMillis() {
        return debounceMillis;
    }

    public void setDebounceMillis(long debounceMillis) {
        this.debounceMillis = debounceMillis;
    }
}
