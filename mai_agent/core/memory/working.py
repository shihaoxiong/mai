"""Working Memory Implementation

Short-term memory for single task execution.
"""

import asyncio
from datetime import datetime, timezone
from typing import Any, Dict, Optional
import threading

from .base import (
    Memory,
    MemoryType,
    TaskContext,
    WorkingMemoryProtocol,
)


class WorkingMemory(Memory, WorkingMemoryProtocol):
    """Working memory implementation

    In-memory key-value store for single task execution.
    Lifecycle equals to single task execution.
    """

    def __init__(self):
        self._store: Dict[str, Any] = {}
        self._context: Optional[TaskContext] = None
        self._lock = threading.Lock()

    def get(self, key: str) -> Any:
        """Get value from working memory"""
        with self._lock:
            return self._store.get(key)

    def set(self, key: str, value: Any) -> None:
        """Set value in working memory"""
        with self._lock:
            self._store[key] = value

    def delete(self, key: str) -> None:
        """Delete value from working memory"""
        with self._lock:
            self._store.pop(key, None)

    def clear(self) -> None:
        """Clear all working memory"""
        with self._lock:
            self._store.clear()
            self._context = None

    def all(self) -> Dict[str, Any]:
        """Get all values"""
        with self._lock:
            return dict(self._store)

    @property
    def context(self) -> Optional[TaskContext]:
        """Get current task context"""
        return self._context

    @context.setter
    def context(self, value: TaskContext) -> None:
        """Set task context"""
        self._context = value

    def get_current_step(self) -> int:
        """Get current step number"""
        return self._context.current_step if self._context else 0

    def set_current_step(self, step: int) -> None:
        """Set current step number"""
        if self._context:
            self._context.current_step = step

    def add_observation(self, observation: str) -> None:
        """Add observation to context"""
        if self._context:
            self._context.observations.append(observation)

    def add_intermediate_result(self, key: str, value: Any) -> None:
        """Add intermediate result"""
        if self._context:
            self._context.intermediate_results[key] = value

    def get_intermediate_result(self, key: str) -> Optional[Any]:
        """Get intermediate result"""
        if self._context:
            return self._context.intermediate_results.get(key)
        return None

    def add_step(self, step: Dict[str, Any]) -> None:
        """Add completed step"""
        if self._context:
            self._context.steps.append(step)

    def get_steps(self) -> list:
        """Get all completed steps"""
        return self._context.steps if self._context else []


class WorkingMemoryManager:
    """Manager for working memory instances"""

    _instances: Dict[str, WorkingMemory] = {}
    _lock = threading.Lock()

    @classmethod
    def get(cls, task_id: str) -> WorkingMemory:
        """Get or create working memory for task"""
        with cls._lock:
            if task_id not in cls._instances:
                cls._instances[task_id] = WorkingMemory()
            return cls._instances[task_id]

    @classmethod
    def release(cls, task_id: str) -> None:
        """Release working memory for task"""
        with cls._lock:
            if task_id in cls._instances:
                cls._instances[task_id].clear()
                del cls._instances[task_id]

    @classmethod
    def clear_all(cls) -> None:
        """Clear all working memories"""
        with cls._lock:
            for memory in cls._instances.values():
                memory.clear()
            cls._instances.clear()
