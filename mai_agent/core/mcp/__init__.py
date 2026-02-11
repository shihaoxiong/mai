"""MCP System Module

Provides Model Context Protocol client and tool implementations.
"""

from .schema import (
    ToolSchema,
    ToolParameter,
    ToolCall,
    ToolRegistry,
    ToolCategory,
)

from .client import MCPClient

from .server_connection import MCPServerConnection, MCPTool

from .manager import MCPServerManager, MCPServerConfig

from .tools.terminal import register_terminal_tools

from .tools.file import register_file_tools


__all__ = [
    "ToolSchema",
    "ToolParameter",
    "ToolCall",
    "ToolRegistry",
    "ToolCategory",
    "MCPClient",
    "MCPServerConnection",
    "MCPTool",
    "MCPServerManager",
    "MCPServerConfig",
    "register_terminal_tools",
    "register_file_tools",
]
