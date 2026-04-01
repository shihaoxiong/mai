package com.mai.deerflow.backend.runtime.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mai.deerflow.backend.runtime.config.RuntimeConfigRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
/**
 * MCP 配置服务。
 *
 * 当前只负责配置层的查询和替换，真正的 MCP 连接生命周期后续再接入。
 */
public class McpConfigService {

    static final String MCP_CONFIG_KEY = "mcp.servers";

    private static final TypeReference<List<McpServerConfig>> MCP_CONFIG_LIST_TYPE = new TypeReference<>() {
    };

    private final RuntimeConfigRepository runtimeConfigRepository;

    public McpConfigService(RuntimeConfigRepository runtimeConfigRepository) {
        this.runtimeConfigRepository = runtimeConfigRepository;
    }

    /**
     * 列出当前保存的 MCP Server 配置。
     */
    public List<McpServerConfig> listServers() {
        return runtimeConfigRepository.find(MCP_CONFIG_KEY, MCP_CONFIG_LIST_TYPE)
                .orElseGet(List::of);
    }

    public List<McpServerConfig> replaceServers(List<McpServerConfig> serverConfigs) {
        return runtimeConfigRepository.save(MCP_CONFIG_KEY, List.copyOf(serverConfigs));
    }
}
