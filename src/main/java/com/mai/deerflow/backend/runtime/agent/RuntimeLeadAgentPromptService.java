package com.mai.deerflow.backend.runtime.agent;

import com.mai.deerflow.backend.runtime.api.RuntimeRunOptions;
import com.mai.deerflow.backend.runtime.contract.UploadRef;
import com.mai.deerflow.backend.runtime.memory.MemoryProfileSection;
import com.mai.deerflow.backend.runtime.memory.MemoryProfileStore;
import com.mai.deerflow.backend.runtime.memory.StructuredMemoryProfile;
import com.mai.deerflow.backend.runtime.skill.SkillDescriptor;
import com.mai.deerflow.backend.runtime.skill.SkillRegistryService;
import com.mai.deerflow.backend.runtime.upload.UploadService;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspace;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 统一组装 runtime lead agent 的线程级 prompt/context。
 *
 * 目标：
 * 1. 保持当前“直接调用 lead agent”的主链路不变
 * 2. 把 skills、uploads、workspace 等上下文集中收口
 * 3. 避免这些规则散落在 `ThreadRuntimeService` 与测试里
 */
@Service
public class RuntimeLeadAgentPromptService {

    private final ThreadWorkspaceService threadWorkspaceService;
    private final UploadService uploadService;
    private final SkillRegistryService skillRegistryService;
    private RuntimeLeadAgentPromptProperties promptProperties = new RuntimeLeadAgentPromptProperties();
    private RuntimeDeferredToolService runtimeDeferredToolService;
    private MemoryProfileStore memoryProfileStore;

    public RuntimeLeadAgentPromptService(ThreadWorkspaceService threadWorkspaceService,
                                         UploadService uploadService,
                                         SkillRegistryService skillRegistryService) {
        this.threadWorkspaceService = threadWorkspaceService;
        this.uploadService = uploadService;
        this.skillRegistryService = skillRegistryService;
    }

    @Autowired(required = false)
    public void setRuntimeDeferredToolService(RuntimeDeferredToolService runtimeDeferredToolService) {
        this.runtimeDeferredToolService = runtimeDeferredToolService;
    }

    @Autowired(required = false)
    public void setMemoryProfileStore(MemoryProfileStore memoryProfileStore) {
        this.memoryProfileStore = memoryProfileStore;
    }

    @Autowired(required = false)
    public void setPromptProperties(RuntimeLeadAgentPromptProperties promptProperties) {
        if (promptProperties != null) {
            this.promptProperties = promptProperties;
        }
    }

    /**
     * 返回 lead agent 的简短角色指令。
     */
    public String instruction() {
        return "You are the Java DeerFlow backend lead agent. Execute directly unless delegation is genuinely helpful.";
    }

    /**
     * 组装某个线程当前运行所需的 system prompt。
     */
    public String systemPrompt(String threadId) {
        return systemPrompt(threadId, RuntimeRunOptions.defaults(), null);
    }

    /**
     * 组装某个线程当前运行所需的 system prompt，并按 run 级选项裁剪提示内容。
     */
    public String systemPrompt(String threadId, RuntimeRunOptions runOptions) {
        return systemPrompt(threadId, runOptions, null);
    }

    /**
     * 组装某个线程当前运行所需的 system prompt，并在已知 userId 时注入结构化长期记忆。
     */
    public String systemPrompt(String threadId, RuntimeRunOptions runOptions, String userId) {
        ThreadWorkspace workspace = workspace(threadId);
        List<UploadRef> uploads = visibleUploads(threadId, workspace);
        List<SkillDescriptor> enabledSkills = skillRegistryService.listSkills().stream()
                .filter(SkillDescriptor::enabled)
                .toList();
        RuntimeRunOptions effectiveRunOptions = runOptions == null ? RuntimeRunOptions.defaults() : runOptions;

        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                <role>
                You are mai, an open-source super agent.
                </role>
                
                <runtime_identity>
                你运行在 Java DeerFlow backend 的 lead agent 主链路中。
                </runtime_identity>

                <agent_soul>
                %s
                </agent_soul>

                <runtime_rules>
                - 如果信息缺失、需求有歧义、方案选择存在风险，先调用 `ask_clarification`，不要带着猜测继续执行。
                - 当前线程的长期记忆可能已经预注入到用户输入中；只在相关时使用，不要机械复述“系统记忆”来源。
                - 所有文件路径都必须视为当前线程私有上下文；不要假设可以访问其他线程或宿主机任意路径。
                - 如果某个上传文件存在 Markdown 视图，请优先使用 Markdown 路径；若没有，再退回原文件路径。
                - 不要编造不存在的 outer graph、隐藏节点或额外恢复层。
                </runtime_rules>

                <thread_workspace>
                - threadId: %s
                - 当前线程虚拟路径约定：
                  - /workspace：线程工作目录
                  - /uploads：线程上传目录
                  - /outputs：线程输出目录
                - 当前线程实际目录：
                  - workspace=%s
                  - uploads=%s
                  - outputs=%s
                </thread_workspace>

                """.formatted(
                normalizeSoul(promptProperties.getAgentSoul()),
                threadId,
                workspacePath(workspace),
                uploadsPath(workspace),
                outputsPath(workspace)
        ));

        appendMemorySection(prompt, userId);
        appendUploadsSection(prompt, uploads);
        appendSkillsSection(prompt, enabledSkills);
        appendDeferredToolsSection(prompt, threadId);
        appendRuntimeModeSection(prompt, effectiveRunOptions);
        appendResearchCitationSection(prompt);
        appendOutputSection(prompt);
        prompt.append("<current_date>").append(LocalDate.now()).append("</current_date>\n");
        return prompt.toString();
    }

    /**
     * 构造注入到“最后一条用户消息”前部的线程级上下文块。
     *
     * 这里只描述当前线程目录与可见上传文件，不修改真正持久化的消息内容。
     */
    public String turnContextBlock(String threadId) {
        ThreadWorkspace workspace = workspace(threadId);
        List<UploadRef> uploads = visibleUploads(threadId, workspace);
        StringBuilder block = new StringBuilder();
        appendThreadDataBlock(block, threadId, workspace);
        block.append('\n');
        appendUploadsSection(block, uploads);
        block.append("""
                <runtime_file_guidance>
                - 上述路径都是当前线程的私有上下文。
                - 如果要读取上传文件，优先使用 markdownVirtualPath；如果为空，再使用 originalVirtualPath。
                - 不要假设这些文件信息会自动持久化进长期记忆。
                </runtime_file_guidance>
                """);
        return block.toString().trim();
    }

    private void appendUploadsSection(StringBuilder prompt, List<UploadRef> uploads) {
        prompt.append("<uploaded_files>\n");
        if (uploads.isEmpty()) {
            prompt.append("- 当前线程没有可见上传文件。\n");
        }
        else {
            prompt.append("- 当前线程可见上传文件如下：\n");
            for (UploadRef upload : uploads) {
                prompt.append("  - ").append(upload.name()).append('\n');
                prompt.append("    - original=").append(upload.originalVirtualPath()).append('\n');
                if (hasText(upload.markdownVirtualPath())) {
                    prompt.append("    - markdown=").append(upload.markdownVirtualPath()).append(" (优先使用)\n");
                }
            }
        }
        prompt.append("</uploaded_files>\n\n");
    }

    private void appendThreadDataBlock(StringBuilder block, String threadId, ThreadWorkspace workspace) {
        block.append("""
                <thread_data>
                - threadId: %s
                - workspace_path: %s
                - uploads_path: %s
                - outputs_path: %s
                </thread_data>
                """.formatted(threadId, workspacePath(workspace), uploadsPath(workspace), outputsPath(workspace)));
    }

    private void appendSkillsSection(StringBuilder prompt, List<SkillDescriptor> enabledSkills) {
        prompt.append("<skill_system>\n");
        prompt.append("""
                - 技能用于提供更优的任务工作流和上下文约束。
                - 如果用户请求明显命中某个技能，优先按技能描述组织方案，而不是直接忽略它。
                - Progressive Loading：先读取技能主说明，再按其中引用的资源逐步展开，不要一开始把所有相关材料都塞进上下文。
                - 如果技能带有 location，优先把它当成技能主入口路径；仅在当前环境具备相应读取能力时再继续展开。
                - 不要编造不存在的技能文件或虚构技能能力。
                """);
        prompt.append("\n<enabled_skills>\n");
        if (enabledSkills.isEmpty()) {
            prompt.append("- 当前没有启用技能。\n");
        }
        else {
            prompt.append("- 当前已启用技能：\n");
            for (SkillDescriptor skill : enabledSkills) {
                prompt.append("  - ")
                        .append(skill.name())
                        .append(" [")
                        .append(skill.id())
                        .append("]")
                        .append(": ")
                        .append(skill.description() == null ? "" : skill.description())
                        .append('\n');
                if (hasText(skill.location())) {
                    prompt.append("    - location=").append(skill.location()).append('\n');
                }
            }
        }
        prompt.append("</enabled_skills>\n");
        prompt.append("</skill_system>\n\n");
    }

    private void appendDeferredToolsSection(StringBuilder prompt, String threadId) {
        if (runtimeDeferredToolService == null) {
            return;
        }

        List<String> deferredToolNames = runtimeDeferredToolService.deferredToolNames(threadId);
        if (deferredToolNames.isEmpty()) {
            return;
        }

        prompt.append("<available-deferred-tools>\n");
        for (String deferredToolName : deferredToolNames) {
            prompt.append(deferredToolName).append('\n');
        }
        prompt.append("</available-deferred-tools>\n\n");
    }

    private void appendRuntimeModeSection(StringBuilder prompt, RuntimeRunOptions runOptions) {
        prompt.append("""
                <clarification_policy>
                - `ask_clarification` 用于主动向用户请求补充信息。
                - 一旦调用该工具，当前 run 会暂停并进入 `WAITING_CLARIFICATION`，等待用户通过 resume 提供补充说明。
                - question 要明确、可执行；如果有候选方案，可以放进 options 里。
                </clarification_policy>
                """);

        if (runOptions != null && runOptions.planModeEnabled()) {
            prompt.append("""

                    <planning_mode>
                    - 当前 run 已启用 plan mode；对于明显复杂的多步骤任务，可以使用待办规划能力持续跟踪进度。
                    - 如果任务很简单，就直接完成，不要为了形式而维护 todo。
                    </planning_mode>
                    """);
        }

        if (runOptions != null && runOptions.subagentEnabled()) {
            prompt.append("""

                    <subtask_policy>
                    - `task` 工具用于委派子任务；当前支持 single、sequential、parallel 三种编排模式。
                    - 如果任务本身足够直接，优先自己完成，不要为了包装而委派。
                    - 子任务状态会回写当前线程的 subtask metadata 与事件流。
                    - 单轮最多只允许有限个 `task` 调用；如果需要更多子任务，请分批发起。
                    </subtask_policy>
                    """);
        }
    }

    private void appendResearchCitationSection(StringBuilder prompt) {
        prompt.append("""

                <research_citation_rules>
                - 如果你的回答依赖外部网页、搜索结果、MCP 返回的外部链接或文档来源，请在对应结论后紧跟可点击链接。
                - 如果输出的是研究型长文，末尾补一个 `Sources` 小节，列出主要参考链接。
                - 不要伪造引用；拿不到链接时就明确说明“没有可直接引用的来源”。
                </research_citation_rules>
                """);
    }

    private void appendOutputSection(StringBuilder prompt) {
        prompt.append("""

                <output_rules>
                - 如果任务要求产出文件、报告或交付件，优先写入当前线程的 `/outputs` 目录。
                - 回复中明确说明你生成了什么，以及对应的线程内路径。
                - 如果只是普通对话回答，不要为了形式强行生成文件。
                </output_rules>
                """);
    }

    private void appendMemorySection(StringBuilder prompt, String userId) {
        if (!hasText(userId) || memoryProfileStore == null) {
            return;
        }

        StructuredMemoryProfile profile = memoryProfileStore.loadProfile(userId.trim());
        if (profile == null || !profile.hasContent()) {
            return;
        }

        prompt.append("<memory>\n");
        appendMemoryLine(prompt, "工作上下文", profile.user().workContext());
        appendMemoryLine(prompt, "个人上下文", profile.user().personalContext());
        appendMemoryLine(prompt, "当前关注点", profile.user().topOfMind());
        appendMemoryLine(prompt, "近期记录", profile.history().recentMonths());
        appendMemoryLine(prompt, "较早上下文", profile.history().earlierContext());
        appendMemoryLine(prompt, "长期背景", profile.history().longTermBackground());
        prompt.append("</memory>\n\n");
    }

    private void appendMemoryLine(StringBuilder prompt, String label, MemoryProfileSection section) {
        if (section == null || !hasText(section.summary())) {
            return;
        }
        prompt.append("- ").append(label).append(": ").append(section.summary()).append('\n');
    }

    private String normalizeSoul(String agentSoul) {
        if (!hasText(agentSoul)) {
            return "你是一个稳健、负责、面向交付的 Java DeerFlow runtime lead agent。";
        }
        return agentSoul.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private ThreadWorkspace workspace(String threadId) {
        return threadWorkspaceService.exists(threadId)
                ? threadWorkspaceService.getWorkspace(threadId)
                : null;
    }

    private List<UploadRef> visibleUploads(String threadId, ThreadWorkspace workspace) {
        return workspace == null ? List.of() : uploadService.listUploads(threadId);
    }

    private String workspacePath(ThreadWorkspace workspace) {
        return workspace == null
                ? "(workspace not initialized)"
                : workspace.workspaceRoot().toAbsolutePath().normalize().toString();
    }

    private String uploadsPath(ThreadWorkspace workspace) {
        return workspace == null
                ? "(uploads not initialized)"
                : workspace.uploadsRoot().toAbsolutePath().normalize().toString();
    }

    private String outputsPath(ThreadWorkspace workspace) {
        return workspace == null
                ? "(outputs not initialized)"
                : workspace.outputsRoot().toAbsolutePath().normalize().toString();
    }
}
