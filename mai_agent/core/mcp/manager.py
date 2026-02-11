"""MCP Server Manager

Manage connections to multiple MCP servers and aggregate their tools.
Supports both programmatic config and standard mcp_config.json format.
"""

import asyncio
import json
import os
from pathlib import Path
from typing import Any, Dict, List, Optional
from dataclasses import dataclass, field
import sys

try:
    from ...logger import get_logger
except ImportError:
    import sys
    sys.path.insert(0, str(Path(__file__).parent.parent.parent))
    from logger import get_logger

from .server_connection import MCPServerConnection, MCPTool


logger = get_logger("mcp_manager")


@dataclass
class MCPServerConfig:
    """MCP Server configuration"""
    name: str
    command: List[str]
    env: Optional[Dict[str, str]] = None
    enabled: bool = True


@dataclass
class MCPConfigFile:
    """MCP config file structure (standard format)"""
    mcpServers: Dict[str, Dict[str, Any]] = field(default_factory=dict)


class MCPServerManager:
    """Manager for MCP server connections"""

    def __init__(
        self,
        config_path: Optional[str] = None,
        auto_load: bool = True
    ):
        self._connections: Dict[str, MCPServerConnection] = {}
        self._all_tools: List[MCPTool] = []
        self._config_path = config_path or self._find_mcp_config()
        self._programmatic_configs: List[MCPServerConfig] = []

    def _find_mcp_config(self) -> Optional[str]:
        """Find MCP config file in standard locations"""
        possible_paths = [
            "mcp_config.json",
            "./mcp_config.json",
            str(Path.home() / ".config" / "mcp.json"),
            str(Path.home() / ".config" / "mcp_config.json"),
            "/etc/mcp.json",
        ]

        for path in possible_paths:
            if Path(path).exists():
                logger.debug(f"Found MCP config at: {path}")
                return path

        logger.debug("No MCP config file found")
        return None

    def add_server(self, config: MCPServerConfig) -> None:
        """Add a server configuration programmatically"""
        self._programmatic_configs.append(config)
        logger.debug(
            "Added programmatic server config",
            name=config.name,
            command=config.command
        )

    def load_config(self, path: Optional[str] = None) -> List[MCPServerConfig]:
        """Load MCP server configurations from JSON file"""
        config_path = path or self._config_path

        if not config_path or not Path(config_path).exists():
            logger.debug("No MCP config file to load")
            return []

        try:
            with open(config_path, 'r', encoding='utf-8') as f:
                config_data = json.load(f)

            mcp_servers = config_data.get("mcpServers", {})
            servers = []

            for name, server_config in mcp_servers.items():
                command = server_config.get("command")
                args = server_config.get("args", [])
                env = server_config.get("env", {})

                if isinstance(command, str):
                    full_command = [command] + args
                elif isinstance(command, list):
                    full_command = command + args
                else:
                    logger.warning(
                        "Invalid command format for MCP server",
                        server=name,
                        command=command
                    )
                    continue

                server = MCPServerConfig(
                    name=name,
                    command=full_command,
                    env=env if env else None,
                    enabled=server_config.get("enabled", True)
                )
                servers.append(server)

            logger.info(
                "Loaded MCP config from file",
                path=config_path,
                servers_count=len(servers)
            )

            return servers

        except json.JSONDecodeError as e:
            logger.error(
                "Failed to parse MCP config file",
                path=config_path,
                error=str(e)
            )
            return []
        except Exception as e:
            logger.error(
                "Failed to load MCP config",
                path=config_path,
                error=str(e)
            )
            return []

    async def connect_server(self, config: MCPServerConfig) -> None:
        """Connect to an MCP server"""
        if not config.enabled:
            logger.info("MCP server disabled", name=config.name)
            return

        connection = MCPServerConnection(
            name=config.name,
            command=config.command,
            env=config.env
        )

        try:
            await connection.connect()
            self._connections[config.name] = connection
            logger.info(
                "MCP server connected successfully",
                name=config.name,
                tools_count=len(connection.get_tools())
            )
        except Exception as e:
            logger.error(
                "Failed to connect MCP server",
                name=config.name,
                error=str(e)
            )

    async def connect_servers(
        self,
        configs: Optional[List[MCPServerConfig]] = None,
        load_from_file: bool = True
    ) -> None:
        """Connect to multiple MCP servers"""
        all_configs = list(self._programmatic_configs)

        if load_from_file:
            file_configs = self.load_config()
            all_configs.extend(file_configs)

        if not all_configs:
            logger.info("No MCP servers configured")
            return

        results = await asyncio.gather(
            *[self.connect_server(config) for config in all_configs],
            return_exceptions=True
        )

        for i, config in enumerate(all_configs):
            if isinstance(results[i], Exception):
                logger.warning(
                    "Server connection completed with error",
                    name=config.name,
                    error=str(results[i])
                )

        self._aggregate_tools()

        connected_count = len([c for c in self._connections.values() if c._connected])
        logger.info(
            "MCP servers initialization complete",
            total_configured=len(all_configs),
            connected=connected_count
        )

    def _aggregate_tools(self) -> None:
        """Aggregate tools from all connected servers"""
        self._all_tools = []
        for name, connection in self._connections.items():
            if not connection._connected:
                continue
            tools = connection.get_tools()
            for tool in tools:
                prefixed_tool = MCPTool(
                    name=f"{name}.{tool.name}",
                    description=f"[{name}] {tool.description}",
                    input_schema=tool.input_schema
                )
                self._all_tools.append(prefixed_tool)

    def list_tools(self) -> List[Dict[str, Any]]:
        """List all available tools from all servers"""
        return [tool.to_schema_dict() for tool in self._all_tools]

    def list_tool_names(self) -> List[str]:
        """List all available tool names"""
        return [tool.name for tool in self._all_tools]

    async def call_tool(self, tool_name: str, arguments: Dict[str, Any]) -> Any:
        """Call a tool on the appropriate MCP server"""
        if "." not in tool_name:
            raise ValueError(f"Invalid tool name format: {tool_name}")

        server_name, tool_short_name = tool_name.split(".", 1)

        if server_name not in self._connections:
            raise ValueError(f"Unknown MCP server: {server_name}")

        connection = self._connections[server_name]
        return await connection.call_tool(tool_short_name, arguments)

    async def close(self) -> None:
        """Close all connections"""
        for name, connection in self._connections.items():
            await connection.close()
        self._connections.clear()
        self._all_tools.clear()
        logger.info("All MCP servers closed")


def create_terminal_server_config() -> MCPServerConfig:
    """Create terminal MCP server configuration"""
    return MCPServerConfig(
        name="terminal",
        command=["npx", "-y", "@modelcontextprotocol/server-terminal", "--project", "./"],
        env={
            "PATH": os.environ.get("PATH", ""),
            "HOME": os.environ.get("HOME", "")
        }
    )


def create_file_server_config() -> MCPServerConfig:
    """Create file MCP server configuration"""
    return MCPServerConfig(
        name="file",
        command=["npx", "-y", "@modelcontextprotocol/server-filesystem", "./"],
        env={
            "PATH": os.environ.get("PATH", ""),
            "HOME": os.environ.get("HOME", "")
        }
    )


def create_websearch_server_config() -> MCPServerConfig:
    """Create web search MCP server configuration"""
    return MCPServerConfig(
        name="websearch",
        command=["npx", "-y", "@modelcontextprotocol/server-puppeteer"],
        env={
            "PATH": os.environ.get("PATH", ""),
            "HOME": os.environ.get("HOME", "")
        }
    )
