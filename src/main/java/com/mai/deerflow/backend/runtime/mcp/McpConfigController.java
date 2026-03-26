package com.mai.deerflow.backend.runtime.mcp;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/mcp/config")
public class McpConfigController {

    private final McpConfigService mcpConfigService;

    public McpConfigController(McpConfigService mcpConfigService) {
        this.mcpConfigService = mcpConfigService;
    }

    @GetMapping
    public List<McpServerConfig> listServers() {
        return mcpConfigService.listServers();
    }

    @PutMapping
    public List<McpServerConfig> replaceServers(@RequestBody List<McpServerConfig> serverConfigs) {
        return mcpConfigService.replaceServers(serverConfigs);
    }
}
