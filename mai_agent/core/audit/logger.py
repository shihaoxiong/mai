"""Audit Logger Implementation

Structured audit logging for agent execution.
"""

import asyncio
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

from ...config import get_settings

try:
    from ...logger import get_logger
except ImportError:
    from logger import get_logger
from .schema import AuditEvent, AuditEventType, TaskAudit


logger = get_logger("audit")


class AuditLogger:
    """Audit logger for agent execution tracking

    Provides:
    - Event logging
    - Task audit trails
    - JSON file storage
    """

    def __init__(self, log_dir: str = "./data/audit"):
        self.log_dir = Path(log_dir)
        self._current_task_audit: Optional[TaskAudit] = None
        self._event_queue: List[AuditEvent] = []
        self._lock = asyncio.Lock()
        self._settings = get_settings()

        self._setup_log_dir()

    def _setup_log_dir(self) -> None:
        """Setup log directory"""
        self.log_dir.mkdir(parents=True, exist_ok=True)

    def start_task(self, task_id: str, goal: str) -> TaskAudit:
        """Start tracking a new task"""
        self._current_task_audit = TaskAudit(
            task_id=task_id,
            goal=goal
        )

        self.log_event(
            event_type=AuditEventType.TASK_STARTED,
            task_id=task_id,
            message=f"Task started: {goal}",
            level="INFO"
        )

        return self._current_task_audit

    def end_task(
        self,
        task_id: str,
        status: str,
        summary: Optional[str] = None
    ) -> TaskAudit:
        """End task tracking"""
        if self._current_task_audit and self._current_task_audit.task_id == task_id:
            self._current_task_audit.completed_at = datetime.now(timezone.utc)
            self._current_task_audit.final_status = status
            self._current_task_audit.summary = summary

            if self._current_task_audit.created_at:
                self._current_task_audit.duration_ms = int(
                    (self._current_task_audit.completed_at - self._current_task_audit.created_at)
                    .total_seconds() * 1000
                )

            self.log_event(
                event_type=AuditEventType.TASK_COMPLETED if status == "success" else AuditEventType.TASK_FAILED,
                task_id=task_id,
                message=f"Task {status}: {summary or ''}",
                level="ERROR" if status == "failed" else "INFO"
            )

            audit = self._current_task_audit
            self._current_task_audit = None
            return audit

        return TaskAudit(task_id=task_id, goal="", final_status=status)

    def log_event(
        self,
        event_type: AuditEventType,
        message: str,
        task_id: str = "",
        plan_id: str = "",
        step_id: str = "",
        level: str = "INFO",
        details: Optional[Dict[str, Any]] = None,
        duration_ms: Optional[int] = None,
        **metadata
    ) -> AuditEvent:
        """Log an audit event"""
        event = AuditEvent(
            event_type=event_type,
            task_id=task_id,
            plan_id=plan_id,
            step_id=step_id,
            level=level,
            message=message,
            details=details or {},
            metadata=metadata,
            duration_ms=duration_ms
        )

        if self._current_task_audit:
            self._current_task_audit.events.append(event)

        self._event_queue.append(event)

        logger.debug(
            "Audit event",
            event_type=event_type.value,
            task_id=task_id,
            message=message
        )

        return event

    def log_step_started(
        self,
        task_id: str,
        plan_id: str,
        step_id: str,
        step_description: str
    ) -> AuditEvent:
        """Log step start"""
        return self.log_event(
            event_type=AuditEventType.STEP_STARTED,
            task_id=task_id,
            plan_id=plan_id,
            step_id=step_id,
            message=f"Step started: {step_description}",
            level="INFO",
            details={"description": step_description}
        )

    def log_step_completed(
        self,
        task_id: str,
        plan_id: str,
        step_id: str,
        result: str,
        duration_ms: int
    ) -> AuditEvent:
        """Log step completion"""
        return self.log_event(
            event_type=AuditEventType.STEP_COMPLETED,
            task_id=task_id,
            plan_id=plan_id,
            step_id=step_id,
            message=f"Step completed: {result[:200]}",
            level="INFO",
            details={"result": result},
            duration_ms=duration_ms
        )

    def log_tool_called(
        self,
        task_id: str,
        plan_id: str,
        step_id: str,
        tool_name: str,
        parameters: Dict[str, Any]
    ) -> AuditEvent:
        """Log tool call"""
        return self.log_event(
            event_type=AuditEventType.TOOL_CALLED,
            task_id=task_id,
            plan_id=plan_id,
            step_id=step_id,
            message=f"Tool called: {tool_name}",
            level="DEBUG",
            details={"tool": tool_name, "parameters": parameters}
        )

    def log_tool_result(
        self,
        task_id: str,
        plan_id: str,
        step_id: str,
        tool_name: str,
        success: bool,
        output: str
    ) -> AuditEvent:
        """Log tool result"""
        return self.log_event(
            event_type=AuditEventType.TOOL_RESULT,
            task_id=task_id,
            plan_id=plan_id,
            step_id=step_id,
            message=f"Tool result: {tool_name} - {'success' if success else 'failed'}",
            level="INFO" if success else "WARNING",
            details={"tool": tool_name, "success": success, "output_preview": output[:500]}
        )

    def log_critic_decision(
        self,
        task_id: str,
        plan_id: str,
        decision: str,
        confidence: float,
        reason: str
    ) -> AuditEvent:
        """Log critic decision"""
        return self.log_event(
            event_type=AuditEventType.CRITIC_DECISION,
            task_id=task_id,
            plan_id=plan_id,
            message=f"Critic decision: {decision} (confidence: {confidence})",
            level="INFO",
            details={"decision": decision, "confidence": confidence, "reason": reason}
        )

    def log_plan_created(
        self,
        task_id: str,
        plan_id: str,
        steps_count: int
    ) -> AuditEvent:
        """Log plan creation"""
        return self.log_event(
            event_type=AuditEventType.PLAN_CREATED,
            task_id=task_id,
            plan_id=plan_id,
            message=f"Plan created with {steps_count} steps",
            level="INFO",
            details={"steps_count": steps_count}
        )

    def log_error(
        self,
        task_id: str,
        error: str,
        step_id: Optional[str] = None,
        details: Optional[Dict[str, Any]] = None
    ) -> AuditEvent:
        """Log error event"""
        return self.log_event(
            event_type=AuditEventType.ERROR,
            task_id=task_id,
            step_id=step_id or "",
            message=f"Error: {error}",
            level="ERROR",
            details=details or {"error": error}
        )

    def get_current_audit(self) -> Optional[TaskAudit]:
        """Get current task audit"""
        return self._current_task_audit

    def get_events(self, task_id: Optional[str] = None) -> List[AuditEvent]:
        """Get audit events"""
        if task_id:
            return [
                e for e in self._event_queue
                if e.task_id == task_id
            ]
        return list(self._event_queue)

    def get_task_audit(self, task_id: str) -> Optional[TaskAudit]:
        """Get complete task audit"""
        if self._current_task_audit and self._current_task_audit.task_id == task_id:
            return self._current_task_audit
        return None

    async def save_current_task(self) -> Optional[str]:
        """Save current task audit to file"""
        if not self._current_task_audit:
            return None

        task_id = self._current_task_audit.task_id
        file_path = self.log_dir / f"{task_id}.json"

        with open(file_path, "w", encoding="utf-8") as f:
            json.dump(
                self._current_task_audit.to_dict(),
                f,
                indent=2,
                ensure_ascii=False
            )

        logger.info(
            "Task audit saved",
            task_id=task_id,
            file_path=str(file_path)
        )

        return str(file_path)

    async def save_all(self) -> List[str]:
        """Save all pending audits to files"""
        saved_files = []

        for event in self._event_queue:
            if event.task_id:
                task_audit = self._get_or_create_task_audit(event.task_id)
                task_audit.events.append(event)

        saved_files.append(await self.save_current_task())

        return [f for f in saved_files if f]

    def _get_or_create_task_audit(self, task_id: str) -> TaskAudit:
        """Get or create task audit"""
        if self._current_task_audit and self._current_task_audit.task_id == task_id:
            return self._current_task_audit

        return TaskAudit(task_id=task_id, goal="")

    def clear(self) -> None:
        """Clear all events"""
        self._event_queue.clear()
        if self._current_task_audit:
            self._current_task_audit.events.clear()

    def get_statistics(self) -> Dict[str, Any]:
        """Get audit statistics"""
        total = len(self._event_queue)
        by_type = {}
        by_level = {}

        for event in self._event_queue:
            event_type = event.event_type.value
            by_type[event_type] = by_type.get(event_type, 0) + 1

            level = event.level
            by_level[level] = by_level.get(level, 0) + 1

        error_count = by_level.get("ERROR", 0)

        return {
            "total_events": total,
            "events_by_type": by_type,
            "events_by_level": by_level,
            "error_count": error_count,
            "current_task": self._current_task_audit.task_id if self._current_task_audit else None
        }

    async def close(self) -> None:
        """Close and cleanup"""
        await self.save_current_task()
        self.clear()
        logger.info("Audit logger closed")
