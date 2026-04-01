package com.mai.deerflow.backend.runtime.agent;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;

import java.util.ArrayList;
import java.util.List;

/**
 * Lead Agent 的装配定义。
 *
 * 通过把模型、工具、hook、interceptor 和持久化配置收敛到一个对象中，
 * 避免各处直接操作 `ReactAgent.builder()`。
 */
public record LeadAgentDefinition(
        String name,
        String description,
        String instruction,
        String systemPrompt,
        ChatModel model,
        ChatOptions chatOptions,
        List<ToolCallback> tools,
        List<Hook> hooks,
        List<Interceptor> interceptors,
        ToolExecutionExceptionProcessor toolExecutionExceptionProcessor,
        BaseCheckpointSaver saver,
        CompileConfig compileConfig,
        boolean releaseThread
) {

    public LeadAgentDefinition {
        tools = List.copyOf(tools);
        hooks = List.copyOf(hooks);
        interceptors = List.copyOf(interceptors);
    }

    /**
     * 以必需的 `ChatModel` 为起点创建定义构造器。
     */
    public static Builder builder(ChatModel model) {
        return new Builder(model);
    }

    public static final class Builder {

        private final ChatModel model;
        private String name = "lead-agent";
        private String description = "";
        private String instruction = "";
        private String systemPrompt = "";
        private ChatOptions chatOptions;
        private final List<ToolCallback> tools = new ArrayList<>();
        private final List<Hook> hooks = new ArrayList<>();
        private final List<Interceptor> interceptors = new ArrayList<>();
        private ToolExecutionExceptionProcessor toolExecutionExceptionProcessor;
        private BaseCheckpointSaver saver;
        private CompileConfig compileConfig;
        private boolean releaseThread;

        private Builder(ChatModel model) {
            this.model = model;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder instruction(String instruction) {
            this.instruction = instruction;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder chatOptions(ChatOptions chatOptions) {
            this.chatOptions = chatOptions;
            return this;
        }

        public Builder addTool(ToolCallback tool) {
            this.tools.add(tool);
            return this;
        }

        public Builder tools(List<ToolCallback> tools) {
            this.tools.clear();
            this.tools.addAll(tools);
            return this;
        }

        public Builder addHook(Hook hook) {
            this.hooks.add(hook);
            return this;
        }

        public Builder hooks(List<Hook> hooks) {
            this.hooks.clear();
            this.hooks.addAll(hooks);
            return this;
        }

        public Builder addInterceptor(Interceptor interceptor) {
            this.interceptors.add(interceptor);
            return this;
        }

        public Builder interceptors(List<Interceptor> interceptors) {
            this.interceptors.clear();
            this.interceptors.addAll(interceptors);
            return this;
        }

        public Builder toolExecutionExceptionProcessor(ToolExecutionExceptionProcessor toolExecutionExceptionProcessor) {
            this.toolExecutionExceptionProcessor = toolExecutionExceptionProcessor;
            return this;
        }

        public Builder saver(BaseCheckpointSaver saver) {
            this.saver = saver;
            return this;
        }

        public Builder compileConfig(CompileConfig compileConfig) {
            this.compileConfig = compileConfig;
            return this;
        }

        public Builder releaseThread(boolean releaseThread) {
            this.releaseThread = releaseThread;
            return this;
        }

        public LeadAgentDefinition build() {
            return new LeadAgentDefinition(
                    name,
                    description,
                    instruction,
                    systemPrompt,
                    model,
                    chatOptions,
                    tools,
                    hooks,
                    interceptors,
                    toolExecutionExceptionProcessor,
                    saver,
                    compileConfig,
                    releaseThread
            );
        }
    }
}
