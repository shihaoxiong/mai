package com.mai.deerflow.backend.runtime.sandbox;

import com.mai.deerflow.backend.runtime.workspace.WorkspaceArea;

import java.util.List;

public interface SandboxProvider {

    CommandExecutionResult execute(CommandExecutionRequest request);

    List<String> listDirectory(String threadId, WorkspaceArea area, String directoryPath);

    String readFile(String threadId, WorkspaceArea area, String filePath);

    void writeFile(String threadId, WorkspaceArea area, String filePath, String content);

    void replaceInFile(String threadId, WorkspaceArea area, String filePath, String target, String replacement);
}
