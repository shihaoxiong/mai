package com.mai.deerflow.backend.runtime.agent;

import com.mai.deerflow.backend.runtime.contract.UploadRef;
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
    private RuntimeDeferredToolService runtimeDeferredToolService;

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
        ThreadWorkspace workspace = workspace(threadId);
        List<UploadRef> uploads = visibleUploads(threadId, workspace);
        List<SkillDescriptor> enabledSkills = skillRegistryService.listSkills().stream()
                .filter(SkillDescriptor::enabled)
                .toList();

        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                <role>
                You are mai, an open-source super agent.
                </role>
                
                <runtime_identity>
                你运行在 Java DeerFlow backend 的 lead agent 主链路中。
                </runtime_identity>

                <runtime_rules>
                - 优先直接完成任务；只有当拆分子任务明显更合适时才调用 `task` 工具。
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

                """.formatted(threadId, workspacePath(workspace), uploadsPath(workspace), outputsPath(workspace)));

        appendUploadsSection(prompt, uploads);
        appendSkillsSection(prompt, enabledSkills);
        appendDeferredToolsSection(prompt, threadId);
        appendSubTaskSection(prompt);
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
        prompt.append("<enabled_skills>\n");
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
                        .append(skill.description())
                        .append('\n');
            }
        }
        prompt.append("</enabled_skills>\n\n");
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

    private void appendSubTaskSection(StringBuilder prompt) {
        prompt.append("""
                <clarification_policy>
                - `ask_clarification` 用于主动向用户请求补充信息。
                - 一旦调用该工具，当前 run 会暂停并进入 `WAITING_CLARIFICATION`，等待用户通过 resume 提供补充说明。
                - question 要明确、可执行；如果有候选方案，可以放进 options 里。
                </clarification_policy>

                <subtask_policy>
                - `task` 工具用于委派子任务；当前支持 single、sequential、parallel 三种编排模式。
                - 如果任务本身足够直接，优先自己完成，不要为了包装而委派。
                - 子任务状态会回写当前线程的 subtask metadata 与事件流。
                </subtask_policy>
                """);
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
