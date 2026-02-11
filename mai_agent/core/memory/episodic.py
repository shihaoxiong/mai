"""Episodic Memory Implementation

Task-level memory with SQLite storage for episode recording and replay.
"""

import asyncio
import json
import sqlite3
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional

from .base import Episode, EpisodicMemoryProtocol
try:
    from ...logger import get_logger
except ImportError:
    import sys
    sys.path.insert(0, str(Path(__file__).parent.parent.parent))
    from logger import get_logger


logger = get_logger("episodic_memory")


class EpisodicMemory(EpisodicMemoryProtocol):
    """Episodic memory implementation with SQLite storage

    Stores complete task episodes for replay and reflection.
    """

    def __init__(self, db_path: str = "./data/memory.db"):
        self.db_path = Path(db_path)
        self._conn: Optional[sqlite3.Connection] = None
        self._lock = asyncio.Lock()

    async def initialize(self) -> None:
        """Initialize database connection and create tables"""
        await self._ensure_connection()
        await self._create_tables()
        logger.info("Episodic memory initialized", db_path=str(self.db_path))

    async def _ensure_connection(self) -> None:
        """Ensure database connection is established"""
        if self._conn is None:
            self.db_path.parent.mkdir(parents=True, exist_ok=True)
            self._conn = sqlite3.connect(str(self.db_path))
            self._conn.row_factory = sqlite3.Row

    async def _create_tables(self) -> None:
        """Create database tables"""
        if self._conn is None:
            await self._ensure_connection()

        cursor = self._conn.cursor()

        cursor.execute("""
            CREATE TABLE IF NOT EXISTS episodes (
                episode_id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL,
                goal TEXT NOT NULL,
                steps TEXT NOT NULL,
                outcome TEXT NOT NULL,
                summary TEXT,
                created_at TEXT NOT NULL,
                duration_ms INTEGER NOT NULL,
                total_steps INTEGER NOT NULL,
                success INTEGER NOT NULL
            )
        """)

        cursor.execute("""
            CREATE INDEX IF NOT EXISTS idx_task_id ON episodes(task_id)
        """)

        cursor.execute("""
            CREATE INDEX IF NOT EXISTS idx_created_at ON episodes(created_at)
        """)

        self._conn.commit()

    async def save_episode(self, episode: Episode) -> str:
        """Save an episode to memory"""
        async with self._lock:
            await self._ensure_connection()

            steps_json = json.dumps(episode.steps, ensure_ascii=False)
            created_at = episode.created_at.isoformat()
            success = 1 if episode.success else 0

            cursor = self._conn.cursor()
            cursor.execute("""
                INSERT OR REPLACE INTO episodes
                (episode_id, task_id, goal, steps, outcome, summary,
                 created_at, duration_ms, total_steps, success)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, (
                episode.episode_id,
                episode.task_id,
                episode.goal,
                steps_json,
                episode.outcome,
                episode.summary,
                created_at,
                episode.duration_ms,
                episode.total_steps,
                success
            ))

            self._conn.commit()
            logger.info(
                "Episode saved",
                episode_id=episode.episode_id,
                task_id=episode.task_id,
                success=episode.success
            )

            return episode.episode_id

    async def get_episode(self, episode_id: str) -> Optional[Episode]:
        """Get an episode by ID"""
        await self._ensure_connection()

        cursor = self._conn.cursor()
        cursor.execute("SELECT * FROM episodes WHERE episode_id = ?", (episode_id,))

        row = cursor.fetchone()
        if row is None:
            return None

        return self._row_to_episode(row)

    def _row_to_episode(self, row: sqlite3.Row) -> Episode:
        """Convert database row to Episode object"""
        return Episode(
            episode_id=row["episode_id"],
            task_id=row["task_id"],
            goal=row["goal"],
            steps=json.loads(row["steps"]),
            outcome=row["outcome"],
            summary=row["summary"],
            created_at=datetime.fromisoformat(row["created_at"]),
            duration_ms=row["duration_ms"],
            total_steps=row["total_steps"],
            success=bool(row["success"])
        )

    async def get_episodes_by_task(self, task_id: str) -> List[Episode]:
        """Get all episodes for a task"""
        await self._ensure_connection()

        cursor = self._conn.cursor()
        cursor.execute(
            "SELECT * FROM episodes WHERE task_id = ? ORDER BY created_at DESC",
            (task_id,)
        )

        return [self._row_to_episode(row) for row in cursor.fetchall()]

    async def search_episodes(self, query: str, limit: int = 10) -> List[Episode]:
        """Search episodes by goal content"""
        await self._ensure_connection()

        cursor = self._conn.cursor()
        cursor.execute(
            "SELECT * FROM episodes WHERE goal LIKE ? ORDER BY created_at DESC LIMIT ?",
            (f"%{query}%", limit)
        )

        return [self._row_to_episode(row) for row in cursor.fetchall()]

    async def list_episodes(
        self,
        limit: int = 100,
        offset: int = 0,
        only_successful: Optional[bool] = None
    ) -> List[Episode]:
        """List episodes with pagination"""
        await self._ensure_connection()

        sql = "SELECT * FROM episodes"
        params: List[Any] = []

        if only_successful is not None:
            sql += " WHERE success = ?"
            params.append(1 if only_successful else 0)

        sql += " ORDER BY created_at DESC LIMIT ? OFFSET ?"
        params.extend([limit, offset])

        cursor = self._conn.cursor()
        cursor.execute(sql, params)

        return [self._row_to_episode(row) for row in cursor.fetchall()]

    async def get_recent_episodes(
        self,
        task_id: str,
        limit: int = 5
    ) -> List[Episode]:
        """Get recent episodes for same task type"""
        await self._ensure_connection()

        cursor = self._conn.cursor()
        cursor.execute("""
            SELECT * FROM episodes
            WHERE task_id = ?
            ORDER BY created_at DESC
            LIMIT ?
        """, (task_id, limit))

        return [self._row_to_episode(row) for row in cursor.fetchall()]

    async def get_stats(self) -> Dict[str, Any]:
        """Get memory statistics"""
        await self._ensure_connection()

        cursor = self._conn.cursor()

        cursor.execute("SELECT COUNT(*) as total FROM episodes")
        total = cursor.fetchone()["total"]

        cursor.execute("SELECT COUNT(*) as success FROM episodes WHERE success = 1")
        success_count = cursor.fetchone()["success"]

        cursor.execute("SELECT AVG(duration_ms) as avg_duration FROM episodes")
        avg_duration = cursor.fetchone()["avg_duration"] or 0

        cursor.execute("SELECT AVG(total_steps) as avg_steps FROM episodes")
        avg_steps = cursor.fetchone()["avg_steps"] or 0

        return {
            "total_episodes": total,
            "successful_episodes": success_count,
            "failed_episodes": total - success_count,
            "success_rate": (success_count / total * 100) if total > 0 else 0,
            "avg_duration_ms": avg_duration,
            "avg_steps": avg_steps
        }

    async def clear(self) -> None:
        """Clear all episodes"""
        async with self._lock:
            await self._ensure_connection()

            cursor = self._conn.cursor()
            cursor.execute("DELETE FROM episodes")
            self._conn.commit()

            logger.info("Episodic memory cleared")

    async def close(self) -> None:
        """Close database connection"""
        if self._conn:
            self._conn.close()
            self._conn = None
            logger.info("Episodic memory connection closed")


@asynccontextmanager
async def create_episodic_memory(db_path: str = "./data/memory.db"):
    """Create and initialize episodic memory"""
    memory = EpisodicMemory(db_path)
    try:
        await memory.initialize()
        yield memory
    finally:
        await memory.close()
