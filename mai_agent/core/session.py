"""Session Management Module

Manages conversation sessions with independent contexts.
"""

import asyncio
import uuid
import sys
from pathlib import Path
from dataclasses import dataclass, field
from datetime import datetime, timezone, timedelta
from typing import Any, Dict, List, Optional
from enum import Enum

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from logger import get_logger


logger = get_logger("session")


class SessionState(Enum):
    """Session state enumeration"""
    ACTIVE = "active"
    IDLE = "idle"
    COMPLETED = "completed"


@dataclass
class Message:
    """Chat message"""
    role: str
    content: str
    timestamp: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass
class SessionContext:
    """Session context for conversation"""
    session_id: str
    messages: List[Message] = field(default_factory=list)
    current_goal: Optional[str] = None
    task_history: List[Dict[str, Any]] = field(default_factory=list)
    state: SessionState = SessionState.ACTIVE
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    last_activity: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    metadata: Dict[str, Any] = field(default_factory=dict)

    def add_message(self, role: str, content: str, **metadata) -> None:
        """Add message to conversation"""
        self.messages.append(Message(
            role=role,
            content=content,
            metadata=metadata
        ))
        self.last_activity = datetime.now(timezone.utc)

    def get_conversation_history(self, max_messages: int = 20) -> str:
        """Get conversation history as formatted string"""
        recent_messages = self.messages[-max_messages:]
        lines = []
        for msg in recent_messages:
            lines.append(f"[{msg.role.upper()}]: {msg.content}")
        return "\n".join(lines)

    def get_last_user_message(self) -> Optional[str]:
        """Get last user message content"""
        for msg in reversed(self.messages):
            if msg.role == "user":
                return msg.content
        return None

    def clear(self) -> None:
        """Clear session context"""
        self.messages.clear()
        self.task_history.clear()
        self.current_goal = None
        self.state = SessionState.IDLE


class SessionManager:
    """Manages multiple sessions with independent contexts"""

    def __init__(self, max_sessions: int = 100):
        self._sessions: Dict[str, SessionContext] = {}
        self._lock = asyncio.Lock()
        self.max_sessions = max_sessions

    async def create_session(self, session_id: Optional[str] = None) -> SessionContext:
        """Create a new session"""
        async with self._lock:
            session_id = session_id or str(uuid.uuid4())

            if len(self._sessions) >= self.max_sessions:
                await self._cleanup_old_sessions()

            session = SessionContext(session_id=session_id)
            self._sessions[session_id] = session

            logger.info(
                "Session created",
                session_id=session_id
            )

            return session

    async def get_session(self, session_id: str) -> Optional[SessionContext]:
        """Get existing session"""
        async with self._lock:
            return self._sessions.get(session_id)

    async def get_or_create_session(
        self,
        session_id: Optional[str]
    ) -> tuple[str, SessionContext]:
        """Get existing session or create new one
        
        Returns:
            Tuple of (session_id, session_context)
        """
        if session_id:
            session = await self.get_session(session_id)
            if session:
                return session_id, session

        new_session = await self.create_session(session_id)
        return new_session.session_id, new_session

    async def end_session(self, session_id: str) -> bool:
        """End a session"""
        async with self._lock:
            if session_id in self._sessions:
                self._sessions[session_id].state = SessionState.COMPLETED
                logger.info(
                    "Session ended",
                    session_id=session_id
                )
                return True
            return False

    async def delete_session(self, session_id: str) -> bool:
        """Delete a session"""
        async with self._lock:
            if session_id in self._sessions:
                self._sessions[session_id].clear()
                del self._sessions[session_id]
                logger.info(
                    "Session deleted",
                    session_id=session_id
                )
                return True
            return False

    async def list_sessions(self, include_completed: bool = False) -> List[Dict[str, Any]]:
        """List all sessions"""
        async with self._lock:
            sessions = []
            for session_id, session in self._sessions.items():
                if not include_completed and session.state == SessionState.COMPLETED:
                    continue

                sessions.append({
                    "session_id": session_id,
                    "state": session.state.value,
                    "message_count": len(session.messages),
                    "created_at": session.created_at.isoformat(),
                    "last_activity": session.last_activity.isoformat(),
                    "has_goal": session.current_goal is not None
                })

            return sessions

    async def cleanup(self, max_idle_minutes: int = 60) -> int:
        """Cleanup old idle sessions"""
        async with self._lock:
            cutoff = datetime.now(timezone.utc) - timedelta(minutes=max_idle_minutes)

            to_delete = []
            for session_id, session in self._sessions.items():
                if session.last_activity < cutoff:
                    to_delete.append(session_id)

            for session_id in to_delete:
                await self.delete_session(session_id)

            return len(to_delete)

    async def _cleanup_old_sessions(self) -> None:
        """Cleanup oldest sessions when limit reached"""
        sorted_sessions = sorted(
            self._sessions.items(),
            key=lambda x: x[1].last_activity
        )

        to_remove = len(self._sessions) - self.max_sessions + 10
        for session_id, _ in sorted_sessions[:to_remove]:
            await self.delete_session(session_id)

    def get_statistics(self) -> Dict[str, Any]:
        """Get session manager statistics"""
        return {
                "total_sessions": len(self._sessions),
                "active_sessions": sum(
                    1 for s in self._sessions.values()
                    if s.state == SessionState.ACTIVE
                ),
                "idle_sessions": sum(
                    1 for s in self._sessions.values()
                    if s.state == SessionState.IDLE
                ),
                "completed_sessions": sum(
                    1 for s in self._sessions.values()
                    if s.state == SessionState.COMPLETED
                )
            }


class GoalInferencer:
    """Infers goal from conversation context"""

    def __init__(self):
        pass

    def infer_goal(
        self,
        messages: List[Message],
        current_goal: Optional[str] = None
    ) -> Optional[str]:
        """Infer the user's intended goal from conversation"""
        if not messages:
            return current_goal

        recent_messages = [m for m in messages[-10:] if m.role in ("user", "assistant")]

        if not recent_messages:
            return current_goal

        if current_goal:
            goal_indicators = [
                "that's all",
                "thank you",
                "任务完成",
                "完成了",
                "done",
                "finished"
            ]

            for msg in recent_messages:
                if msg.role == "user":
                    if any(ind in msg.content.lower() for ind in goal_indicators):
                        return None

        last_user_msg = None
        for msg in reversed(recent_messages):
            if msg.role == "user":
                last_user_msg = msg.content
                break

        if last_user_msg:
            imperative_indicators = [
                "请",
                "帮我",
                "我想",
                "能不能",
                "请帮我",
                "可以帮我",
                "would you",
                "please",
                "can you"
            ]

            for indicator in imperative_indicators:
                if indicator in last_user_msg:
                    return last_user_msg

            if len(last_user_msg) < 200:
                goal_indicators = [
                    "是什么",
                    "怎么做",
                    "帮我",
                    "告诉我",
                    "请",
                    "总结",
                    "分析",
                    "解释",
                    "write",
                    "create",
                    "make",
                    "do"
                ]

                if any(ind in last_user_msg.lower() for ind in goal_indicators):
                    return last_user_msg

        return current_goal or (last_user_msg if last_user_msg else None)

    def detect_task_switch(self, messages: List[Message]) -> bool:
        """Detect if user is switching to a new task"""
        if len(messages) < 2:
            return False

        last_user = None
        for msg in reversed(messages):
            if msg.role == "user":
                last_user = msg.content
                break

        if not last_user:
            return False

        switch_indicators = [
            "换个任务",
            "新任务",
            "另一个",
            "换一个",
            "different task",
            "new task",
            "another"
        ]

        return any(ind in last_user.lower() for ind in switch_indicators)

    def extract_keywords(self, message: str, max_keywords: int = 5) -> List[str]:
        """Extract key topics from message"""
        import re

        stopwords = {
            "的", "了", "是", "在", "我", "你", "他", "她", "它",
            "这个", "那个", "什么", "怎么", "如何", "为什么",
            "the", "a", "an", "is", "are", "was", "were",
            "to", "of", "in", "for", "with", "on", "at"
        }

        words = re.findall(r'\b[a-zA-Z\u4e00-\u9fff]+\b', message.lower())

        keywords = [
            w for w in words
            if len(w) > 2 and w not in stopwords
        ][:max_keywords]

        return keywords
