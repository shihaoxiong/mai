package com.mai.deerflow.backend.runtime.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mai.deerflow.backend.runtime.config.RuntimeConfigRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class McpConfigService {

    static final String MCP_CONFIG_KEY = "mcp.servers";

    private static final TypeReference<List<McpServerConfig>> MCP_CONFIG_LIST_TYPE = new TypeReference<>() {
    };

    private final RuntimeConfigRepository runtimeConfigRepository;

    public McpConfigService(RuntimeConfigRepository runtimeConfigRepository) {
        this.runtimeConfigRepository = runtimeConfigRepository;
    }

    public List<McpServerConfig> listServers() {
        return runtimeConfigRepository.find(MCP_CONFIG_KEY, MCP_CONFIG_LIST_TYPE)
                .orElseGet(List::of);
    }

    public List<McpServerConfig> replaceServers(List<McpServerConfig> serverConfigs) {
        return runtimeConfigRepository.save(MCP_CONFIG_KEY, List.copyOf(serverConfigs));
    }
}
