package com.mai.deerflow.backend.runtime.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mai.deerflow.backend.runtime.mcp.RuntimeMcpToolProvider;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspace;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceService;
import com.mai.deerflow.backend.runtime.workspace.WorkspaceArea;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 为 runtime lead agent 提供最小可用的 deferred tools / tool_search 能力。
 */
@Service
public class RuntimeDeferredToolService {

    private final ThreadWorkspaceService threadWorkspaceService;
    private final ObjectMapper objectMapper;
    private RuntimeMcpToolProvider runtimeMcpToolProvider;

    public RuntimeDeferredToolService(ThreadWorkspaceService threadWorkspaceService, ObjectMapper objectMapper) {
        this.threadWorkspaceService = threadWorkspaceService;
        this.objectMapper = objectMapper;
    }

    @Autowired(required = false)
    public void setRuntimeMcpToolProvider(RuntimeMcpToolProvider runtimeMcpToolProvider) {
        this.runtimeMcpToolProvider = runtimeMcpToolProvider;
    }

    public List<ToolCallback> deferredTools(String threadId) {
        List<ToolCallback> tools = new ArrayList<>();
        tools.add(listThreadFilesTool(threadId));
        tools.add(readThreadFileTool(threadId));
        if (runtimeMcpToolProvider != null) {
            tools.addAll(runtimeMcpToolProvider.loadTools());
        }
        return List.copyOf(tools);
    }

    public ToolCallback toolSearchTool(String threadId, List<ToolCallback> deferredTools) {
        return FunctionToolCallback
                .builder("tool_search", (DeferredToolSearchRequest request) -> search(threadId, request, deferredTools))
                .description("""
                        Fetch the full schema definitions for deferred tools so they become callable in the next turn.
                        Query forms:
                        - select:name1,name2
                        - +keyword ranking terms
                        - free-text / regex keyword search
                        """)
                .inputType(DeferredToolSearchRequest.class)
                .build();
    }

    public List<String> deferredToolNames(String threadId) {
        return deferredTools(threadId).stream()
                .map(tool -> tool.getToolDefinition().name())
                .toList();
    }

    private String search(String threadId, DeferredToolSearchRequest request, List<ToolCallback> deferredTools) {
        RuntimeDeferredToolRegistry registry = registryOf(deferredTools);
        if (registry.entries().isEmpty()) {
            return "No deferred tools available.";
        }

        String query = request == null ? null : request.query();
        List<ToolCallback> matchedTools = registry.search(query == null ? "" : query);
        if (matchedTools.isEmpty()) {
            return "No tools found matching: " + query;
        }

        try {
            List<Map<String, Object>> toolDefinitions = new ArrayList<>();
            for (ToolCallback tool : matchedTools) {
                toolDefinitions.add(toolDefinition(tool));
            }
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(toolDefinitions);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize deferred tool definitions", exception);
        }
    }

    private RuntimeDeferredToolRegistry registryOf(List<ToolCallback> deferredTools) {
        RuntimeDeferredToolRegistry registry = new RuntimeDeferredToolRegistry();
        deferredTools.forEach(registry::register);
        return registry;
    }

    private Map<String, Object> toolDefinition(ToolCallback tool) throws IOException {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", tool.getToolDefinition().name());
        definition.put("description", tool.getToolDefinition().description());
        JsonNode parameters = objectMapper.readTree(tool.getToolDefinition().inputSchema());
        definition.put("parameters", parameters);
        return definition;
    }

    private ToolCallback listThreadFilesTool(String threadId) {
        return FunctionToolCallback
                .builder("list_thread_files", (ListThreadFilesRequest request) -> listThreadFiles(threadId, request))
                .description("List files available in the current thread workspace. area can be workspace, uploads, outputs, or omitted for all.")
                .inputType(ListThreadFilesRequest.class)
                .build();
    }

    private ToolCallback readThreadFileTool(String threadId) {
        return FunctionToolCallback
                .builder("read_thread_file", (ReadThreadFileRequest request) -> readThreadFile(threadId, request))
                .description("Read a UTF-8 text file from the current thread workspace by virtual path.")
                .inputType(ReadThreadFileRequest.class)
                .build();
    }

    private String listThreadFiles(String threadId, ListThreadFilesRequest request) {
        ThreadWorkspace workspace = threadWorkspaceService.getOrCreateWorkspace(threadId);
        List<Path> roots = rootsForArea(workspace, request == null ? null : request.area());
        List<Map<String, Object>> files = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile)
                        .sorted()
                        .forEach(path -> files.add(Map.of(
                                "path", threadWorkspaceService.toVirtualPath(threadId, path),
                                "size", safeSize(path)
                        )));
            }
            catch (IOException exception) {
                throw new IllegalStateException("Failed to list thread files for " + threadId, exception);
            }
        }

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(files);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize listed files", exception);
        }
    }

    private long safeSize(Path path) {
        try {
            return Files.size(path);
        }
        catch (IOException exception) {
            return -1L;
        }
    }

    private List<Path> rootsForArea(ThreadWorkspace workspace, String area) {
        if (area == null || area.isBlank()) {
            return List.of(workspace.workspaceRoot(), workspace.uploadsRoot(), workspace.outputsRoot());
        }

        WorkspaceArea workspaceArea = WorkspaceArea.valueOf(area.trim().toUpperCase(Locale.ROOT));
        return List.of(workspace.rootFor(workspaceArea));
    }

    private String readThreadFile(String threadId, ReadThreadFileRequest request) {
        String path = request == null ? null : request.path();
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }

        Path realPath = threadWorkspaceService.resolveVirtualPath(threadId, path);
        if (!Files.isRegularFile(realPath)) {
            throw new IllegalArgumentException("File not found: " + path);
        }

        try {
            byte[] bytes = Files.readAllBytes(realPath);
            if (containsZeroByte(bytes)) {
                throw new IllegalArgumentException("File appears to be binary and cannot be read as text: " + path);
            }

            String content = new String(bytes, StandardCharsets.UTF_8);
            int maxChars = request == null || request.maxChars() == null || request.maxChars() < 1
                    ? 16_000
                    : request.maxChars();
            if (content.length() <= maxChars) {
                return content;
            }
            return content.substring(0, maxChars) + "\n...[truncated]";
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to read file " + path, exception);
        }
    }

    private boolean containsZeroByte(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }
}
