"""Memory System Base Classes

Base classes and interfaces for the memory system.
"""

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Protocol
from enum import Enum
import uuid


class MemoryType(str, Enum):
    """Memory type enumeration"""
    WORKING = "working"
    EPISODIC = "episodic"
    LONG_TERM = "long_term"


@dataclass
class Memory:
    """Base memory entry"""
    id: str = field(default_factory=lambda: str(uuid.uuid4()))
    memory_type: MemoryType = MemoryType.WORKING
    content: str = ""
    metadata: Dict[str, Any] = field(default_factory=dict)
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    updated_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    importance_score: float = 0.0


@dataclass
class TaskContext:
    """Task execution context"""
    task_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    goal: str = ""
    steps: List[Dict[str, Any]] = field(default_factory=list)
    current_step: int = 0
    intermediate_results: Dict[str, Any] = field(default_factory=dict)
    observations: List[str] = field(default_factory=list)


@dataclass
class Episode:
    """Episode record for episodic memory"""
    episode_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    task_id: str = ""
    goal: str = ""
    steps: List[Dict[str, Any]] = field(default_factory=list)
    outcome: str = ""
    summary: str = ""
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    duration_ms: int = 0
    total_steps: int = 0
    success: bool = False


class WorkingMemoryProtocol(Protocol):
    """Protocol for working memory"""

    def get(self, key: str) -> Any: ...
    def set(self, key: str, value: Any) -> None: ...
    def delete(self, key: str) -> None: ...
    def clear(self) -> None: ...
    def all(self) -> Dict[str, Any]: ...


class EpisodicMemoryProtocol(Protocol):
    """Protocol for episodic memory"""

    async def save_episode(self, episode: Episode) -> str: ...
    async def get_episode(self, episode_id: str) -> Optional[Episode]: ...
    async def get_episodes_by_task(self, task_id: str) -> List[Episode]: ...
    async def search_episodes(self, query: str, limit: int = 10) -> List[Episode]: ...
    async def list_episodes(self, limit: int = 100, offset: int = 0) -> List[Episode]: ...


class LongTermMemoryProtocol(Protocol):
    """Protocol for long-term memory"""

    async def add(self, content: str, metadata: Dict[str, Any]) -> str: ...
    async def search(self, query: str, limit: int = 5) -> List[Dict[str, Any]]: ...
    async def get(self, memory_id: str) -> Optional[Dict[str, Any]]: ...
    async def delete(self, memory_id: str) -> bool: ...
    async def clear(self) -> None: ...


class MemorySystem(ABC):
    """Abstract base class for memory system"""

    @abstractmethod
    async def initialize(self) -> None:
        """Initialize memory system"""
        pass

    @abstractmethod
    async def close(self) -> None:
        """Close and cleanup resources"""
        pass
