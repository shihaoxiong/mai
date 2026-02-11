"""Memory System Module

Provides working memory, episodic memory, and long-term memory implementations.
"""

from .base import (
    Memory,
    MemoryType,
    TaskContext,
    Episode,
    MemorySystem,
    WorkingMemoryProtocol,
    EpisodicMemoryProtocol,
    LongTermMemoryProtocol,
)

from .working import WorkingMemory, WorkingMemoryManager

from .episodic import EpisodicMemory, create_episodic_memory

from .long_term import LongTermMemory, InMemoryLongTermMemory


__all__ = [
    "Memory",
    "MemoryType",
    "TaskContext",
    "Episode",
    "MemorySystem",
    "WorkingMemoryProtocol",
    "EpisodicMemoryProtocol",
    "LongTermMemoryProtocol",
    "WorkingMemory",
    "WorkingMemoryManager",
    "EpisodicMemory",
    "create_episodic_memory",
    "LongTermMemory",
    "InMemoryLongTermMemory",
]
