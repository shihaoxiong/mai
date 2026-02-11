"""MCP Server Connection

Native MCP client implementation for connecting to MCP servers.
Supports stdio transport (JSON-RPC 2.0).
"""

import asyncio
import json
import subprocess
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Callable
from dataclasses import dataclass, field
from datetime import datetime, timezone

try:
    from ...logger import get_logger
except ImportError:
    import sys
    sys.path.insert(0, str(Path(__file__).parent.parent.parent))
    from logger import get_logger


logger = get_logger("mcp_connection")


@dataclass
class MCPTool:
    """MCP Tool definition"""
    name: str
    description: str
    input_schema: Dict[str, Any] = field(default_factory=dict)

    @classmethod
    def from_mcp(cls, data: Dict[str, Any]) -> "MCPTool":
        return cls(
            name=data.get("name", ""),
            description=data.get("description", ""),
            input_schema=data.get("inputSchema", {})
        )

    def to_schema_dict(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "description": self.description,
            "parameters": self.input_schema
        }


class MCPServerConnection:
    """Connection to an MCP server via stdio transport"""

    def __init__(
        self,
        name: str,
        command: List[str],
        env: Optional[Dict[str, str]] = None
    ):
        self.name = name
        self.command = command
        self.env = env or {}
        self._process: Optional[subprocess.Popen] = None
        self._request_id = 0
        self._tools: List[MCPTool] = []
        self._connected = False

    async def connect(self) -> None:
        """Start MCP server and initialize connection"""
        logger.info(
            "Starting MCP server",
            name=self.name,
            command=" ".join(self.command)
        )

        env = {**os.environ, **self.env} if self.env else None

        self._process = subprocess.Popen(
            self.command,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            env=env
        )

        await self._initialize()

        self._connected = True
        logger.info(
            "MCP server connected",
            name=self.name,
            tools_count=len(self._tools)
        )

    async def _initialize(self) -> None:
        """Send initialize request to MCP server"""
        init_request = {
            "jsonrpc": "2.0",
            "id": self._next_id(),
            "method": "initialize",
            "params": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {
                    "name": "mai-agent",
                    "version": "1.0.0"
                }
            }
        }

        response = await self._send_request(init_request)
        logger.debug(
            "MCP server initialized",
            name=self.name,
            server_info=response.get("result", {}).get("serverInfo")
        )

        await self._list_tools()

    async def _list_tools(self) -> List[MCPTool]:
        """Request list of available tools from server"""
        request = {
            "jsonrpc": "2.0",
            "id": self._next_id(),
            "method": "tools/list",
            "params": {}
        }

        response = await self._send_request(request)
        tools_data = response.get("result", {}).get("tools", [])

        self._tools = [MCPTool.from_mcp(t) for t in tools_data]
        logger.info(
            "Tools loaded from MCP server",
            name=self.name,
            tools=[t.name for t in self._tools]
        )

        return self._tools

    async def call_tool(self, name: str, arguments: Dict[str, Any]) -> Any:
        """Call a tool on the MCP server"""
        request = {
            "jsonrpc": "2.0",
            "id": self._next_id(),
            "method": "tools/call",
            "params": {
                "name": name,
                "arguments": arguments
            }
        }

        response = await self._send_request(request)
        return response.get("result")

    async def _send_request(self, request: Dict[str, Any]) -> Dict[str, Any]:
        """Send JSON-RPC request and get response"""
        if not self._process:
            raise RuntimeError(f"MCP server {self.name} not connected")

        request_str = json.dumps(request) + "\n"

        loop = asyncio.get_event_loop()
        stdin_write = loop.run_in_executor(None, self._process.stdin.write, request_str)
        await stdin_write
        self._process.stdin.flush()

        response_line = await loop.run_in_executor(None, self._process.stdout.readline)

        if not response_line:
            raise RuntimeError(f"MCP server {self.name} disconnected")

        return json.loads(response_line.strip())

    def _next_id(self) -> int:
        self._request_id += 1
        return self._request_id

    def get_tools(self) -> List[MCPTool]:
        """Get list of available tools"""
        return self._tools

    async def close(self) -> None:
        """Close connection to MCP server"""
        if self._process:
            self._process.terminate()
            try:
                self._process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self._process.kill()
            self._process = None

        self._connected = False
        logger.info("MCP server closed", name=self.name)


import os
