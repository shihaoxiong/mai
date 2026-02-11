"""MCP Schema Definitions

Models for Model Context Protocol tool definitions.
"""

from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Dict, List, Optional
from uuid import uuid4


class ToolCategory(str, Enum):
    """Tool category enumeration"""
    SEARCH = "search"
    CODE = "code"
    DATABASE = "database"
    FILE = "file"
    SYSTEM = "system"
    CUSTOM = "custom"


@dataclass
class ToolParameter:
    """Tool parameter definition"""
    name: str
    type: str
    description: str
    required: bool = False
    default: Optional[Any] = None
    enum_values: Optional[List[str]] = None


@dataclass
class ToolSchema:
    """MCP Tool schema definition"""
    name: str
    description: str
    category: ToolCategory = ToolCategory.CUSTOM
    parameters: List[ToolParameter] = field(default_factory=list)
    output_schema: Optional[Dict[str, Any]] = None
    side_effect: bool = False
    permission: str = "read"
    examples: List[Dict[str, Any]] = field(default_factory=list)


@dataclass
class ToolCall:
    """Tool call record"""
    call_id: str = field(default_factory=lambda: str(uuid4()))
    tool_name: str = ""
    parameters: Dict[str, Any] = field(default_factory=dict)
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    duration_ms: Optional[int] = None
    success: bool = False
    output: Optional[str] = None
    error: Optional[str] = None


@dataclass
class ToolRegistry:
    """Registry of available tools"""
    tools: Dict[str, ToolSchema] = field(default_factory=dict)

    def register(self, tool: ToolSchema) -> None:
        self.tools[tool.name] = tool

    def unregister(self, tool_name: str) -> bool:
        if tool_name in self.tools:
            del self.tools[tool_name]
            return True
        return False

    def get(self, tool_name: str) -> Optional[ToolSchema]:
        return self.tools.get(tool_name)

    def list_tools(self) -> List[str]:
        return list(self.tools.keys())

    def list_by_category(self, category: ToolCategory) -> List[str]:
        return [
            name for name, tool in self.tools.items()
            if tool.category == category
        ]

    def clear(self) -> None:
        """Clear all registered tools"""
        self.tools.clear()

    def list_all(self) -> List[ToolSchema]:
        """List all tools"""
        return list(self.tools.values())
