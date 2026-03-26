package com.mai.deerflow.backend.runtime.sandbox;

import com.mai.deerflow.backend.runtime.workspace.WorkspaceArea;

import java.util.List;

/**
 * sandbox 能力抽象。
 */
public interface SandboxProvider {

    /**
     * 在指定线程目录中执行命令。
     */
    CommandExecutionResult execute(CommandExecutionRequest request);

    /**
     * 列出指定目录下的子项名称。
     */
    List<String> listDirectory(String threadId, WorkspaceArea area, String directoryPath);

    /**
     * 读取线程工作区中的文件内容。
     */
    String readFile(String threadId, WorkspaceArea area, String filePath);

    /**
     * 写入线程工作区中的文件内容。
     */
    void writeFile(String threadId, WorkspaceArea area, String filePath, String content);

    /**
     * 在文件中执行简单字符串替换。
     */
    void replaceInFile(String threadId, WorkspaceArea area, String filePath, String target, String replacement);
}
