"""Executor Schema Definitions

Pydantic models for execution, parameters, and results.
"""

from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Dict, List, Optional
from uuid import uuid4


class ExecutionStatus(str, Enum):
    """Execution status enumeration"""
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class ErrorCategory(str, Enum):
    """Error category enumeration"""
    VALIDATION = "validation"
    TIMEOUT = "timeout"
    PERMISSION = "permission"
    NOT_FOUND = "not_found"
    UNKNOWN = "unknown"
    RETRYABLE = "retryable"


@dataclass
class ExecutionResult:
    """Result of executing a step"""
    success: bool
    output: Optional[str] = None
    error: Optional[str] = None
    error_category: Optional[ErrorCategory] = None
    duration_ms: int = 0
    retry_count: int = 0
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass
class StepExecution:
    """Execution record for a single step"""
    execution_id: str = field(default_factory=lambda: str(uuid4()))
    step_id: str = ""
    plan_id: str = ""
    task_id: str = ""
    tool: Optional[str] = None
    parameters: Dict[str, Any] = field(default_factory=dict)
    status: ExecutionStatus = ExecutionStatus.PENDING
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    duration_ms: Optional[int] = None
    result: Optional[ExecutionResult] = None
    retry_count: int = 0
    max_retries: int = 3

    def start(self) -> None:
        self.status = ExecutionStatus.RUNNING
        self.started_at = datetime.now(timezone.utc)

    def complete(self, result: ExecutionResult) -> None:
        self.result = result
        self.completed_at = datetime.now(timezone.utc)
        if self.started_at:
            self.duration_ms = int(
                (self.completed_at - self.started_at).total_seconds() * 1000
            )
        self.status = ExecutionStatus.COMPLETED if result.success else ExecutionStatus.FAILED

    def cancel(self) -> None:
        self.status = ExecutionStatus.CANCELLED
        self.completed_at = datetime.now(timezone.utc)

    def can_retry(self) -> bool:
        return (
            self.status == ExecutionStatus.FAILED
            and self.retry_count < self.max_retries
            and self.result
            and self.result.error_category == ErrorCategory.RETRYABLE
        )

    def to_dict(self) -> Dict[str, Any]:
        return {
            "execution_id": self.execution_id,
            "step_id": self.step_id,
            "plan_id": self.plan_id,
            "task_id": self.task_id,
            "tool": self.tool,
            "parameters": self.parameters,
            "status": self.status.value,
            "started_at": self.started_at.isoformat() if self.started_at else None,
            "completed_at": self.completed_at.isoformat() if self.completed_at else None,
            "duration_ms": self.duration_ms,
            "result": {
                "success": self.result.success if self.result else False,
                "output": self.result.output if self.result else None,
                "error": self.result.error if self.result else None,
                "error_category": self.result.error_category.value if self.result and self.result.error_category else None,
                "duration_ms": self.result.duration_ms if self.result else 0,
                "retry_count": self.result.retry_count if self.result else 0
            } if self.result else None,
            "retry_count": self.retry_count
        }


@dataclass
class ExecutionConfig:
    """Configuration for execution"""
    max_retries: int = 3
    retry_delay_base: float = 1.0
    retry_delay_max: float = 60.0
    execution_timeout: int = 300
    validation_strict: bool = True


@dataclass
class ExecutionContext:
    """Context for execution"""
    task_id: str = ""
    plan_id: str = ""
    current_step: int = 0
    execution_history: List[StepExecution] = field(default_factory=list)
    config: ExecutionConfig = field(default_factory=ExecutionConfig)
