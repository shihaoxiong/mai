"""Audit Log Schema Definitions

Models for audit logging and trace management.
"""

from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Dict, List, Optional
from uuid import uuid4


class AuditEventType(str, Enum):
    """Audit event type enumeration"""
    TASK_STARTED = "task_started"
    TASK_COMPLETED = "task_completed"
    TASK_FAILED = "task_failed"
    PLAN_CREATED = "plan_created"
    PLAN_UPDATED = "plan_updated"
    STEP_STARTED = "step_started"
    STEP_COMPLETED = "step_completed"
    STEP_FAILED = "step_failed"
    TOOL_CALLED = "tool_called"
    TOOL_RESULT = "tool_result"
    CRITIC_DECISION = "critic_decision"
    MEMORY_WRITE = "memory_write"
    ERROR = "error"
    INFO = "info"


@dataclass
class AuditEvent:
    """Audit event record"""
    event_id: str = field(default_factory=lambda: str(uuid4()))
    event_type: AuditEventType = AuditEventType.INFO
    timestamp: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    task_id: str = ""
    plan_id: str = ""
    step_id: str = ""
    agent_name: str = "mai-agent"
    level: str = "INFO"
    message: str = ""
    details: Dict[str, Any] = field(default_factory=dict)
    metadata: Dict[str, Any] = field(default_factory=dict)
    duration_ms: Optional[int] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "event_id": self.event_id,
            "event_type": self.event_type.value,
            "timestamp": self.timestamp.isoformat(),
            "task_id": self.task_id,
            "plan_id": self.plan_id,
            "step_id": self.step_id,
            "agent_name": self.agent_name,
            "level": self.level,
            "message": self.message,
            "details": self.details,
            "metadata": self.metadata,
            "duration_ms": self.duration_ms
        }


@dataclass
class TaskAudit:
    """Complete audit record for a task"""
    task_id: str
    goal: str
    events: List[AuditEvent] = field(default_factory=list)
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    completed_at: Optional[datetime] = None
    duration_ms: Optional[int] = None
    final_status: str = "pending"
    summary: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "task_id": self.task_id,
            "goal": self.goal,
            "events": [e.to_dict() for e in self.events],
            "created_at": self.created_at.isoformat(),
            "completed_at": self.completed_at.isoformat() if self.completed_at else None,
            "duration_ms": self.duration_ms,
            "final_status": self.final_status,
            "summary": self.summary
        }
