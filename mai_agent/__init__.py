"""MAI Agent - Production-grade Conversational AI System

A modular agent framework with session management and goal inference.
"""

__version__ = "1.0.0"

from .config import get_settings, Settings
from .logger import get_logger

from .core.agent.agent import (
    ConversationalAgent,
    AgentState,
    AgentConfig,
    AgentStats,
    create_agent,
)

from .core.session import (
    SessionManager,
    SessionContext,
    SessionState,
    Message,
    GoalInferencer,
)

from .core.memory.session_memory import (
    InMemoryContext,
    SessionEpisodicMemory,
    ContextManager,
)


__all__ = [
    "__version__",
    "get_settings",
    "Settings",
    "get_logger",
    "ConversationalAgent",
    "AgentState",
    "AgentConfig",
    "AgentStats",
    "create_agent",
    "SessionManager",
    "SessionContext",
    "SessionState",
    "Message",
    "GoalInferencer",
    "InMemoryContext",
    "SessionEpisodicMemory",
    "ContextManager",
]
