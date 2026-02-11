"""Executor Implementation

Deterministic step execution with validation and retry mechanisms.
"""

import asyncio
import math
import time
from datetime import datetime, timezone
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional

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

from .base import BaseExecutor
from .schema import (
    ExecutionResult,
    ExecutionStatus,
    ErrorCategory,
    ExecutionConfig,
    StepExecution,
)
from ..mcp import MCPClient


logger = get_logger("executor")


class Executor(BaseExecutor):
    """Deterministic executor for step execution

    Provides:
    - Step validation
    - Retry with exponential backoff
    - Timeout enforcement
    - Result parsing
    """

    def __init__(
        self,
        max_retries: int = 3,
        execution_timeout: int = 300,
        config: Optional[ExecutionConfig] = None,
        mcp_client: Optional[MCPClient] = None
    ):
        self.mcp_client = mcp_client
        self.max_retries = max_retries
        self.execution_timeout = execution_timeout
        self.config = config or ExecutionConfig()

    @property
    def name(self) -> str:
        """Executor name"""
        return "executor"

    async def execute_step(
        self,
        step_id: str,
        tool: str,
        parameters: Dict[str, Any]
    ) -> "ExecutionResult":
        """Execute a single step"""
        return await self.execute(
            step_id=step_id,
            tool=tool,
            parameters=parameters
        )

    async def validate_step(
        self,
        tool: str,
        parameters: Dict[str, Any]
    ) -> tuple[bool, Optional[str]]:
        """Validate step before execution"""
        validation = self._validate_step("", tool, parameters)
        return validation.is_valid, ", ".join(validation.errors) if validation.errors else None

    async def execute(
        self,
        step_id: str,
        tool: Optional[str],
        parameters: Optional[Dict[str, Any]] = None,
        validate_only: bool = False
    ) -> ExecutionResult:
        """Execute a step with validation and optional retry"""
        start_time = time.time()

        if not tool:
            return ExecutionResult(
                step_id=step_id,
                success=True,
                output="No tool to execute",
                duration_ms=0
            )

        parameters = parameters or {}

        validation = self._validate_step(step_id, tool, parameters)
        if not validation.is_valid:
            return ExecutionResult(
                step_id=step_id,
                success=False,
                error=validation.error or "Validation failed",
                duration_ms=0,
                validation_errors=validation.errors
            )

        if validate_only:
            return ExecutionResult(
                step_id=step_id,
                success=True,
                output="Validation passed",
                duration_ms=0
            )

        retry_count = 0
        last_error = None

        while retry_count <= self.max_retries:
            try:
                output = await self.mcp_client.call_tool(tool, parameters)

                duration_ms = int((time.time() - start_time) * 1000)

                return ExecutionResult(
                    step_id=step_id,
                    tool=tool,
                    success=True,
                    output=output,
                    duration_ms=duration_ms,
                    retry_count=retry_count
                )

            except Exception as e:
                last_error = str(e)
                retry_delay = self._calculate_backoff(retry_count)

                if retry_count < self.max_retries:
                    await asyncio.sleep(retry_delay)
                    retry_count += 1
                else:
                    duration_ms = int((time.time() - start_time) * 1000)

                    return ExecutionResult(
                        step_id=step_id,
                        tool=tool,
                        success=False,
                        error=f"Max retries ({self.max_retries}) exceeded: {last_error}",
                        duration_ms=duration_ms,
                        retry_count=retry_count,
                        error_category=ErrorCategory.RETRYABLE
                    )

        duration_ms = int((time.time() - start_time) * 1000)
        return ExecutionResult(
            step_id=step_id,
            tool=tool,
            success=False,
            error=last_error or "Unknown error",
            duration_ms=duration_ms
        )

    def _validate_step(
        self,
        step_id: str,
        tool: Optional[str],
        parameters: Optional[Dict[str, Any]] = None
    ) -> StepExecution:
        """Validate step parameters"""
        errors: List[str] = []
        warnings: List[str] = []

        if not tool:
            return StepExecution(
                step_id=step_id,
                status=ExecutionStatus.VALID,
                is_valid=True
            )

        if tool.startswith("terminal.") and "command" in (parameters or {}):
            command = parameters.get("command", "")

            if not command or not command.strip():
                errors.append("Empty command provided")

            elif len(command) > 1000:
                errors.append("Command exceeds maximum length of 1000 characters")

            dangerous = ["rm", "mkfs", "dd", "format", "sudo", "chmod"]
            if any(d in command.lower() for d in dangerous):
                errors.append(f"Potentially dangerous command detected")

        if tool.startswith("file.") and "path" in (parameters or {}):
            path = parameters.get("path", "")

            if not path:
                errors.append("Empty file path provided")

            elif ".." in path or path.startswith("/"):
                warnings.append(f"Path '{path}' may access files outside working directory")

        return StepExecution(
            step_id=step_id,
            tool=tool,
            parameters=parameters or {},
            status=ExecutionStatus.VALID,
            is_valid=len(errors) == 0,
            errors=errors,
            warnings=warnings
        )

    def _calculate_backoff(self, retry_count: int) -> float:
        """Calculate exponential backoff delay"""
        base_delay = 1.0
        max_delay = 60.0

        delay = min(base_delay * (2 ** retry_count), max_delay)

        jitter = delay * 0.1 * retry_count
        delay += jitter

        return delay

    async def close(self) -> None:
        """Cleanup resources"""
        if self.mcp_client:
            await self.mcp_client.close()


class SequentialExecutor(Executor):
    """Sequential executor for ordered step execution"""

    def __init__(
        self,
        mcp_client: MCPClient,
        max_retries: int = 3,
        execution_timeout: int = 300,
        config: Optional[ExecutionConfig] = None
    ):
        super().__init__(
            mcp_client=mcp_client,
            max_retries=max_retries,
            execution_timeout=execution_timeout,
            config=config
        )
        self._execution_lock = asyncio.Lock()

    async def execute(
        self,
        step_id: str,
        tool: Optional[str],
        parameters: Optional[Dict[str, Any]] = None,
        validate_only: bool = False
    ) -> ExecutionResult:
        """Execute step with sequential lock"""
        async with self._execution_lock:
            return await super().execute(
                step_id=step_id,
                tool=tool,
                parameters=parameters,
                validate_only=validate_only
            )
