"""Executor Base Classes

Abstract base classes for step execution.
"""

from abc import ABC, abstractmethod
from typing import Any, Dict, Optional, Protocol


class ToolExecutorProtocol(Protocol):
    """Protocol for tool executors"""

    @abstractmethod
    async def execute(
        self,
        tool_name: str,
        parameters: Dict[str, Any]
    ) -> "ExecutionResult":
        """Execute a tool with given parameters"""
        pass

    @abstractmethod
    async def validate_parameters(
        self,
        tool_name: str,
        parameters: Dict[str, Any]
    ) -> tuple[bool, Optional[str]]:
        """Validate parameters for a tool"""
        pass

    @abstractmethod
    def get_available_tools(self) -> list[str]:
        """Get list of available tools"""
        pass


class BaseExecutor(ABC):
    """Abstract base class for executors"""

    @property
    @abstractmethod
    def name(self) -> str:
        """Executor name"""
        pass

    @abstractmethod
    async def execute_step(
        self,
        step_id: str,
        tool: str,
        parameters: Dict[str, Any]
    ) -> "ExecutionResult":
        """Execute a single step"""
        pass

    @abstractmethod
    async def validate_step(
        self,
        tool: str,
        parameters: Dict[str, Any]
    ) -> tuple[bool, Optional[str]]:
        """Validate step before execution"""
        pass

    @abstractmethod
    async def close(self) -> None:
        """Cleanup resources"""
        pass
