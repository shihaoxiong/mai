"""Planner Schema Definitions

Pydantic models for plans, steps, and planning requests/responses.
"""

from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Dict, List, Optional
from uuid import uuid4


class PlanStatus(str, Enum):
    """Plan status enumeration"""
    PENDING = "pending"
    IN_PROGRESS = "in_progress"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class StepStatus(str, Enum):
    """Step status enumeration"""
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    SKIPPED = "skipped"


class StepType(str, Enum):
    """Step type enumeration"""
    ACTION = "action"
    THOUGHT = "thought"
    VERIFICATION = "verification"
    DECISION = "decision"


@dataclass
class PlanStep:
    """Individual step in a plan"""
    step_number: int
    description: str
    step_id: str = field(default_factory=lambda: f"step_{uuid4().hex[:8]}")
    type: StepType = StepType.ACTION
    tool: Optional[str] = None
    parameters: Dict[str, Any] = field(default_factory=dict)
    expected_output: Optional[str] = None
    status: StepStatus = StepStatus.PENDING
    result: Optional[str] = None
    error: Optional[str] = None
    retry_count: int = 0
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    duration_ms: Optional[int] = None

    def mark_running(self) -> None:
        self.status = StepStatus.RUNNING
        self.started_at = datetime.now(timezone.utc)

    def mark_completed(self, result: str) -> None:
        self.status = StepStatus.COMPLETED
        self.result = result
        self.completed_at = datetime.now(timezone.utc)
        if self.started_at:
            self.duration_ms = int(
                (self.completed_at - self.started_at).total_seconds() * 1000
            )

    def mark_failed(self, error: str) -> None:
        self.status = StepStatus.FAILED
        self.error = error
        self.completed_at = datetime.now(timezone.utc)
        if self.started_at:
            self.duration_ms = int(
                (self.completed_at - self.started_at).total_seconds() * 1000
            )


@dataclass
class Plan:
    """Plan consisting of multiple steps"""
    task_id: str
    goal: str
    plan_id: str = field(default_factory=lambda: str(uuid4()))
    description: Optional[str] = None
    steps: List[PlanStep] = field(default_factory=list)
    current_step: int = 0
    status: PlanStatus = PlanStatus.PENDING
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    updated_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    completed_at: Optional[datetime] = None
    outcome: Optional[str] = None
    summary: Optional[str] = None
    metadata: Dict[str, Any] = field(default_factory=dict)

    def add_step(self, step: PlanStep) -> None:
        self.steps.append(step)
        self.updated_at = datetime.now(timezone.utc)

    def get_next_step(self) -> Optional[PlanStep]:
        if self.current_step < len(self.steps):
            return self.steps[self.current_step]
        return None

    def get_completed_steps(self) -> List[PlanStep]:
        return [s for s in self.steps if s.status == StepStatus.COMPLETED]

    def get_failed_steps(self) -> List[PlanStep]:
        return [s for s in self.steps if s.status == StepStatus.FAILED]

    def mark_in_progress(self) -> None:
        self.status = PlanStatus.IN_PROGRESS
        self.updated_at = datetime.now(timezone.utc)

    def mark_completed(self, summary: str) -> None:
        self.status = PlanStatus.COMPLETED
        self.completed_at = datetime.now(timezone.utc)
        self.updated_at = datetime.now(timezone.utc)
        self.summary = summary

    def mark_failed(self, outcome: str) -> None:
        self.status = PlanStatus.FAILED
        self.completed_at = datetime.now(timezone.utc)
        self.updated_at = datetime.now(timezone.utc)
        self.outcome = outcome

    def to_dict(self) -> Dict[str, Any]:
        return {
            "plan_id": self.plan_id,
            "task_id": self.task_id,
            "goal": self.goal,
            "description": self.description,
            "steps": [
                {
                    "step_id": s.step_id,
                    "step_number": s.step_number,
                    "type": s.type.value,
                    "description": s.description,
                    "tool": s.tool,
                    "parameters": s.parameters,
                    "expected_output": s.expected_output,
                    "status": s.status.value,
                    "result": s.result,
                    "error": s.error,
                    "retry_count": s.retry_count,
                    "duration_ms": s.duration_ms
                }
                for s in self.steps
            ],
            "status": self.status.value,
            "current_step": self.current_step,
            "created_at": self.created_at.isoformat(),
            "updated_at": self.updated_at.isoformat(),
            "completed_at": self.completed_at.isoformat() if self.completed_at else None,
            "outcome": self.outcome,
            "summary": self.summary,
            "metadata": self.metadata
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "Plan":
        steps = []
        for s_data in data.get("steps", []):
            step = PlanStep(
                step_id=s_data.get("step_id", ""),
                step_number=s_data.get("step_number", 0),
                type=StepType(s_data.get("type", "action")),
                description=s_data.get("description", ""),
                tool=s_data.get("tool"),
                parameters=s_data.get("parameters", {}),
                expected_output=s_data.get("expected_output"),
                status=StepStatus(s_data.get("status", "pending")),
                result=s_data.get("result"),
                error=s_data.get("error"),
                retry_count=s_data.get("retry_count", 0),
                duration_ms=s_data.get("duration_ms")
            )
            if s_data.get("started_at"):
                step.started_at = datetime.fromisoformat(s_data["started_at"])
            if s_data.get("completed_at"):
                step.completed_at = datetime.fromisoformat(s_data["completed_at"])
            steps.append(step)

        return cls(
            plan_id=data.get("plan_id", str(uuid4())),
            task_id=data.get("task_id", ""),
            goal=data.get("goal", ""),
            description=data.get("description"),
            steps=steps,
            status=PlanStatus(data.get("status", "pending")),
            current_step=data.get("current_step", 0)
        )


@dataclass
class PlanningRequest:
    """Request to create a plan"""
    task_id: str
    goal: str
    context: Optional[str] = None
    available_tools: List[str] = field(default_factory=list)
    constraints: List[str] = field(default_factory=list)
    max_steps: int = 10
    metadata: Dict[str, Any] = field(default_factory=dict)

    def validate(self) -> None:
        """Validate request parameters"""
        if self.max_steps < 1:
            raise ValueError("max_steps must be at least 1")
        if not self.task_id:
            raise ValueError("task_id is required")
        if not self.goal:
            raise ValueError("goal is required")


@dataclass
class PlanningResponse:
    """Response from planning"""
    plan: Plan
    reasoning: str
    confidence: float
    warnings: List[str] = field(default_factory=list)
    suggestions: List[str] = field(default_factory=list)
