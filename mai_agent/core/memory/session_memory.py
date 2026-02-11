"""Simplified Memory System

In-memory storage for session-based context.
"""

import asyncio
import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional
import uuid

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from logger import get_logger


logger = get_logger("memory")


class InMemoryContext:
    """In-memory context for single session"""

    def __init__(self, session_id: str):
        self.session_id = session_id
        self._data: Dict[str, Any] = {}
        self._history: List[Dict[str, Any]] = []
        self._observations: List[str] = []
        self.created_at = datetime.now(timezone.utc)
        self.updated_at = datetime.now(timezone.utc)

    def set(self, key: str, value: Any) -> None:
        self._data[key] = value
        self._history.append({
            "action": "set",
            "key": key,
            "value": value,
            "timestamp": datetime.now(timezone.utc).isoformat()
        })
        self.updated_at = datetime.now(timezone.utc)

    def get(self, key: str) -> Any:
        return self._data.get(key)

    def delete(self, key: str) -> None:
        if key in self._data:
            del self._data[key]
            self._history.append({
                "action": "delete",
                "key": key,
                "timestamp": datetime.now(timezone.utc).isoformat()
            })

    def add_observation(self, observation: str) -> None:
        self._observations.append(observation)
        self.updated_at = datetime.now(timezone.utc)

    def get_observations(self) -> List[str]:
        return list(self._observations)

    def get_history(self) -> List[Dict[str, Any]]:
        return list(self._history)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "session_id": self.session_id,
            "data": self._data,
            "observations": self._observations,
            "created_at": self.created_at.isoformat(),
            "updated_at": self.updated_at.isoformat()
        }

    def clear(self) -> None:
        self._data.clear()
        self._history.clear()
        self._observations.clear()
        self.updated_at = datetime.now(timezone.utc)


class SessionEpisodicMemory:
    """Episodic memory for session - stores completed tasks"""

    def __init__(self, db_path: str = "./data/session_memory.db"):
        self.db_path = Path(db_path)
        self._conn = None
        self._lock = asyncio.Lock()

    async def initialize(self) -> None:
        """Initialize database"""
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(str(self.db_path))
        self._conn.row_factory = sqlite3.Row

        cursor = self._conn.cursor()
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS sessions (
                session_id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL,
                goal TEXT NOT NULL,
                outcome TEXT NOT NULL,
                summary TEXT,
                created_at TEXT NOT NULL,
                duration_ms INTEGER,
                success INTEGER
            )
        """)
        self._conn.commit()

        logger.info("Session episodic memory initialized", db_path=str(self.db_path))

    async def save_session(
        self,
        session_id: str,
        task_id: str,
        goal: str,
        outcome: str,
        summary: Optional[str] = None,
        duration_ms: Optional[int] = None,
        success: bool = True
    ) -> str:
        """Save session result"""
        async with self._lock:
            if self._conn is None:
                await self.initialize()

            cursor = self._conn.cursor()
            cursor.execute("""
                INSERT OR REPLACE INTO sessions
                (session_id, task_id, goal, outcome, summary, created_at, duration_ms, success)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, (
                session_id,
                task_id,
                goal,
                outcome,
                summary,
                datetime.now(timezone.utc).isoformat(),
                duration_ms,
                1 if success else 0
            ))
            self._conn.commit()

            return session_id

    async def get_session_history(
        self,
        session_id: str,
        limit: int = 10
    ) -> List[Dict[str, Any]]:
        """Get session history"""
        if self._conn is None:
            await self.initialize()

        cursor = self._conn.cursor()
        cursor.execute("""
            SELECT * FROM sessions
            WHERE session_id = ?
            ORDER BY created_at DESC
            LIMIT ?
        """, (session_id, limit))

        return [dict(row) for row in cursor.fetchall()]

    async def get_all_sessions(self, limit: int = 100) -> List[Dict[str, Any]]:
        """Get all sessions"""
        if self._conn is None:
            await self.initialize()

        cursor = self._conn.cursor()
        cursor.execute("""
            SELECT * FROM sessions
            ORDER BY created_at DESC
            LIMIT ?
        """, (limit,))

        return [dict(row) for row in cursor.fetchall()]

    async def clear_session(self, session_id: str) -> bool:
        """Clear session from memory"""
        async with self._lock:
            if self._conn is None:
                await self.initialize()

            cursor = self._conn.cursor()
            cursor.execute("DELETE FROM sessions WHERE session_id = ?", (session_id,))
            self._conn.commit()

            return cursor.rowcount > 0

    async def get_statistics(self) -> Dict[str, Any]:
        """Get memory statistics"""
        if self._conn is None:
            await self.initialize()

        cursor = self._conn.cursor()
        cursor.execute("SELECT COUNT(*) as total FROM sessions")
        total = cursor.fetchone()["total"]

        cursor.execute("SELECT COUNT(*) as success FROM sessions WHERE success = 1")
        success_count = cursor.fetchone()["success"]

        return {
            "total_sessions": total,
            "successful_sessions": success_count,
            "failed_sessions": total - success_count,
            "success_rate": (success_count / total * 100) if total > 0 else 0
        }

    async def close(self) -> None:
        """Close connection"""
        if self._conn:
            self._conn.close()
            self._conn = None


class ContextManager:
    """Manages in-memory contexts for multiple sessions"""

    def __init__(self):
        self._contexts: Dict[str, InMemoryContext] = {}
        self._lock = asyncio.Lock()

    async def get_context(self, session_id: str) -> InMemoryContext:
        """Get or create context for session"""
        async with self._lock:
            if session_id not in self._contexts:
                self._contexts[session_id] = InMemoryContext(session_id)
            return self._contexts[session_id]

    async def delete_context(self, session_id: str) -> bool:
        """Delete session context"""
        async with self._lock:
            if session_id in self._contexts:
                self._contexts[session_id].clear()
                del self._contexts[session_id]
                return True
            return False

    async def clear_all(self) -> None:
        """Clear all contexts"""
        async with self._lock:
            for ctx in self._contexts.values():
                ctx.clear()
            self._contexts.clear()

    def get_active_sessions(self) -> List[str]:
        """Get list of active session IDs"""
        return list(self._contexts.keys())

    def get_statistics(self) -> Dict[str, Any]:
        """Get statistics"""
        return {
            "active_sessions": len(self._contexts),
            "session_ids": list(self._contexts.keys())
        }
