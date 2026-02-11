"""MCP Client Implementation

Client for Model Context Protocol tool execution.
"""

import asyncio
import json
from datetime import datetime, timezone
from pathlib import Path
import sys
from typing import Any, Dict, List, Optional
from uuid import uuid4

try:
    from ...config import get_settings
except ImportError:
    import sys
    from pathlib import Path
    sys.path.insert(0, str(Path(__file__).parent.parent.parent))
    from config import get_settings

try:
    from ...logger import get_logger
except ImportError:
    import sys
    sys.path.insert(0, str(Path(__file__).parent.parent.parent))
    from logger import get_logger

from .schema import (
    ToolSchema,
    ToolCall,
    ToolParameter,
    ToolRegistry,
)


logger = get_logger("mcp_client")


class MCPClient:
    """MCP Client for tool execution

    Handles:
    - Tool registration
    - Tool calling
    - Schema validation
    """

    def __init__(self):
        self._registry = ToolRegistry()
        self._connected = False

    async def initialize(self) -> None:
        """Initialize client and register built-in tools"""
        logger.info("Initializing MCP client")

        self._connected = True

        logger.info("MCP client initialized")

    async def connect(self) -> None:
        """Connect to MCP server"""
        await self.initialize()

    async def close(self) -> None:
        """Close connection"""
        self._connected = False
        self._registry.clear()

    def register_tool(self, schema: ToolSchema) -> None:
        """Register a tool schema"""
        self._registry.register(schema)

        logger.debug(
            "Tool registered",
            name=schema.name,
            description=schema.description[:50]
        )

    def unregister_tool(self, name: str) -> bool:
        """Unregister a tool"""
        return self._registry.unregister(name)

    def get_tool(self, name: str) -> Optional[ToolSchema]:
        """Get tool schema by name"""
        return self._registry.get(name)

    def list_tools(self) -> List[Dict[str, Any]]:
        """List all registered tools"""
        return [tool.to_dict() for tool in self._registry.list_all()]

    async def call_tool(
        self,
        tool_name: str,
        parameters: Optional[Dict[str, Any]] = None
    ) -> str:
        """Call a tool by name"""
        schema = self._registry.get(tool_name)
        if not schema:
            raise ValueError(f"Tool not found: {tool_name}")

        parameters = parameters or {}

        for param in schema.parameters:
            if param.required and param.name not in parameters:
                raise ValueError(f"Missing required parameter: {param.name}")

        if schema.name.startswith("terminal."):
            return await self._execute_terminal_tool(schema.name, parameters)
        elif schema.name.startswith("file."):
            return await self._execute_file_tool(schema.name, parameters)
        else:
            raise ValueError(f"Unknown tool type: {schema.name}")

    async def _execute_terminal_tool(
        self,
        tool_name: str,
        parameters: Dict[str, Any]
    ) -> str:
        """Execute terminal tool"""
        if tool_name == "terminal.execute":
            command = parameters.get("command", "")
            if not command:
                raise ValueError("Missing command parameter")

            try:
                import subprocess
                result = subprocess.run(
                    command,
                    shell=True,
                    capture_output=True,
                    text=True,
                    timeout=60,
                    cwd=Path.cwd()
                )

                output = result.stdout
                if result.stderr and not output:
                    output = result.stderr

                return output if output else "(command executed successfully)"

            except subprocess.TimeoutExpired:
                raise TimeoutError(f"Command timed out: {command}")

        elif tool_name == "terminal.ls":
            path = parameters.get("path", ".")
            try:
                import subprocess
                result = subprocess.run(
                    f"ls -la {path}",
                    shell=True,
                    capture_output=True,
                    text=True
                )
                return result.stdout

            except Exception as e:
                return f"Error listing directory: {str(e)}"

        elif tool_name == "terminal.cat":
            path = parameters.get("path", "")
            if not path:
                raise ValueError("Missing path parameter")

            p = Path(path)
            if not p.exists():
                raise FileNotFoundError(f"File not found: {path}")

            if p.stat().st_size > 10000:
                raise ValueError(f"File too large: {p.stat().st_size} bytes")

            try:
                return p.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                with open(p, "rb") as f:
                    binary_content = f.read()
                return f"[Binary file - {len(binary_content)} bytes]"

        else:
            raise ValueError(f"Unknown terminal tool: {tool_name}")

    async def _execute_file_tool(
        self,
        tool_name: str,
        parameters: Dict[str, Any]
    ) -> str:
        """Execute file tool"""
        if tool_name == "file.read":
            path = parameters.get("path", "")
            if not path:
                raise ValueError("Missing path parameter")

            p = Path(path)
            if not p.exists():
                raise FileNotFoundError(f"File not found: {path}")

            if p.stat().st_size > 10000:
                raise ValueError(f"File too large: {p.stat().st_size} bytes")

            try:
                return p.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                with open(p, "rb") as f:
                    binary_content = f.read()
                return f"[Binary file - {len(binary_content)} bytes]"

        elif tool_name == "file.write":
            path = parameters.get("path", "")
            content = parameters.get("content", "")
            encoding = parameters.get("encoding", "utf-8")

            if not path:
                raise ValueError("Missing path parameter")

            p = Path(path)
            p.parent.mkdir(parents=True, exist_ok=True)

            p.write_text(content, encoding=encoding)

            return f"File written: {path}"

        elif tool_name == "file.ls":
            path = parameters.get("path", ".")
            p = Path(path)

            if not p.exists():
                raise FileNotFoundError(f"Directory not found: {path}")

            if not p.is_dir():
                raise NotADirectoryError(f"Not a directory: {path}")

            items = []
            for item in p.iterdir():
                items.append(f"{'d' if item.is_dir() else '-'} {item.name}")

            return "\n".join(sorted(items))

        else:
            raise ValueError(f"Unknown file tool: {tool_name}")
