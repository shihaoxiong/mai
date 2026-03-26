package com.mai.deerflow.backend.runtime.mcp;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/mcp/config")
/**
 * MCP 配置管理入口。
 */
public class McpConfigController {

    private final McpConfigService mcpConfigService;

    public McpConfigController(McpConfigService mcpConfigService) {
        this.mcpConfigService = mcpConfigService;
    }

    /**
     * 查询当前 MCP Server 配置列表。
     */
    @GetMapping
    public List<McpServerConfig> listServers() {
        return mcpConfigService.listServers();
    }

    /**
     * 用新的配置列表整体替换现有 MCP 配置。
     */
    @PutMapping
    public List<McpServerConfig> replaceServers(@RequestBody List<McpServerConfig> serverConfigs) {
        return mcpConfigService.replaceServers(serverConfigs);
    }
}
