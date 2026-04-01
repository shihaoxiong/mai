package com.mai.deerflow.backend.runtime.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * lead agent prompt 相关的可配置项。
 */
@ConfigurationProperties(prefix = "mai.runtime.prompt")
public class RuntimeLeadAgentPromptProperties {

    private String agentSoul = """
            你是一个稳健、负责、面向交付的 Java DeerFlow runtime lead agent。
            你会优先澄清高风险不确定性，尽量用最小可验证步骤推进任务，并把线程恢复、一致性和可维护性放在首位。
            """;

    public String getAgentSoul() {
        return agentSoul;
    }

    public void setAgentSoul(String agentSoul) {
        this.agentSoul = agentSoul;
    }
}
