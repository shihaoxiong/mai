"""Test for Memory Module

Tests for working, episodic, and long-term memory.
"""

import pytest
import asyncio
from pathlib import Path
from datetime import datetime, timezone

from mai_agent.core.memory import (
    WorkingMemory,
    WorkingMemoryManager,
    EpisodicMemory,
    LongTermMemory,
    Episode,
    MemoryType,
)


class TestWorkingMemory:
    """Tests for working memory"""

    @pytest.fixture
    def working_memory(self):
        """Create a working memory instance"""
        return WorkingMemory()

    def test_set_and_get(self, working_memory):
        """Test basic set and get operations"""
        working_memory.set("key1", "value1")
        assert working_memory.get("key1") == "value1"

    def test_delete(self, working_memory):
        """Test delete operation"""
        working_memory.set("key1", "value1")
        working_memory.delete("key1")
        assert working_memory.get("key1") is None

    def test_clear(self, working_memory):
        """Test clear operation"""
        working_memory.set("key1", "value1")
        working_memory.set("key2", "value2")
        working_memory.clear()
        assert working_memory.get("key1") is None
        assert working_memory.get("key2") is None

    def test_all(self, working_memory):
        """Test getting all values"""
        working_memory.set("key1", "value1")
        working_memory.set("key2", "value2")
        all_values = working_memory.all()
        assert all_values == {"key1": "value1", "key2": "value2"}

    def test_context(self, working_memory):
        """Test task context operations"""
        from mai_agent.core.memory import TaskContext

        context = TaskContext(
            task_id="test-task",
            goal="Test goal"
        )
        working_memory.context = context

        assert working_memory.context.task_id == "test-task"
        assert working_memory.context.goal == "Test goal"

    def test_intermediate_results(self, working_memory):
        """Test intermediate result operations"""
        working_memory.add_intermediate_result("result1", "data1")
        working_memory.add_intermediate_result("result2", 42)

        assert working_memory.get_intermediate_result("result1") == "data1"
        assert working_memory.get_intermediate_result("result2") == 42

    def test_observations(self, working_memory):
        """Test observation operations"""
        working_memory.add_observation("Observation 1")
        working_memory.add_observation("Observation 2")

        assert len(working_memory.context.observations) == 2


class TestWorkingMemoryManager:
    """Tests for working memory manager"""

    def test_get_instance(self):
        """Test getting memory instance for task"""
        task_id = "test-task-123"
        memory = WorkingMemoryManager.get(task_id)

        assert memory is not None
        assert isinstance(memory, WorkingMemory)

        WorkingMemoryManager.release(task_id)

    def test_release(self):
        """Test releasing memory"""
        task_id = "test-task-456"
        memory = WorkingMemoryManager.get(task_id)
        memory.set("key", "value")

        WorkingMemoryManager.release(task_id)

        memory2 = WorkingMemoryManager.get(task_id)
        assert memory2.get("key") is None

        WorkingMemoryManager.release(task_id)


class TestEpisodicMemory:
    """Tests for episodic memory"""

    @pytest.fixture
    def episodic_memory(self, tmp_path):
        """Create episodic memory with temp database"""
        from mai_agent.core.memory import EpisodicMemory

        db_path = str(tmp_path / "test_memory.db")
        memory = EpisodicMemory(db_path=db_path)
        yield memory

    @pytest.mark.asyncio
    async def test_save_and_get_episode(self, episodic_memory):
        """Test saving and retrieving episode"""
        await episodic_memory.initialize()

        episode = Episode(
            episode_id="test-episode-1",
            task_id="test-task-1",
            goal="Test goal",
            steps=[{"step_number": 1, "description": "Test step"}],
            outcome="success",
            summary="Test completed",
            success=True
        )

        episode_id = await episodic_memory.save_episode(episode)
        assert episode_id == "test-episode-1"

        retrieved = await episodic_memory.get_episode("test-episode-1")
        assert retrieved is not None
        assert retrieved.goal == "Test goal"
        assert retrieved.success is True

    @pytest.mark.asyncio
    async def test_search_episodes(self, episodic_memory):
        """Test searching episodes"""
        await episodic_memory.initialize()

        for i in range(5):
            episode = Episode(
                episode_id=f"test-episode-{i}",
                task_id="test-task-search",
                goal=f"Test goal {i}",
                steps=[],
                outcome="success",
                success=True
            )
            await episodic_memory.save_episode(episode)

        results = await episodic_memory.search_episodes("goal 2", limit=10)
        assert len(results) >= 1

    @pytest.mark.asyncio
    async def test_get_episodes_by_task(self, episodic_memory):
        """Test getting episodes by task ID"""
        await episodic_memory.initialize()

        for i in range(3):
            episode = Episode(
                episode_id=f"test-episode-task-{i}",
                task_id="test-task-same",
                goal=f"Goal {i}",
                steps=[],
                outcome="success",
                success=True
            )
            await episodic_memory.save_episode(episode)

        results = await episodic_memory.get_episodes_by_task("test-task-same")
        assert len(results) == 3

    @pytest.mark.asyncio
    async def test_list_episodes(self, episodic_memory):
        """Test listing episodes with pagination"""
        await episodic_memory.initialize()

        for i in range(5):
            episode = Episode(
                episode_id=f"test-episode-list-{i}",
                task_id=f"task-{i}",
                goal=f"Goal {i}",
                steps=[],
                outcome="success",
                success=True
            )
            await episodic_memory.save_episode(episode)

        results = await episodic_memory.list_episodes(limit=3, offset=0)
        assert len(results) == 3

    @pytest.mark.asyncio
    async def test_get_stats(self, episodic_memory):
        """Test getting memory statistics"""
        await episodic_memory.initialize()

        for i in range(3):
            episode = Episode(
                episode_id=f"test-episode-stats-{i}",
                task_id=f"task-{i}",
                goal=f"Goal {i}",
                steps=[],
                outcome="success",
                success=True
            )
            await episodic_memory.save_episode(episode)

        stats = await episodic_memory.get_stats()
        assert "total_episodes" in stats
        assert stats["total_episodes"] == 3


class TestLongTermMemory:
    """Tests for long-term memory"""

    @pytest.fixture
    def long_term_memory(self, tmp_path):
        """Create long-term memory with temp storage"""
        from mai_agent.core.memory import LongTermMemory

        chroma_path = str(tmp_path / "chroma")
        memory = LongTermMemory(chroma_path=chroma_path)
        yield memory

    @pytest.mark.asyncio
    async def test_add_memory(self, long_term_memory):
        """Test adding memory"""
        await long_term_memory.initialize()

        memory_id = await long_term_memory.add(
            content="Test knowledge",
            metadata={"category": "test"}
        )

        assert memory_id is not None

    @pytest.mark.asyncio
    async def test_search_memory(self, long_term_memory):
        """Test searching memories"""
        await long_term_memory.initialize()

        await long_term_memory.add(
            content="Python is a programming language",
            metadata={"category": "programming"}
        )

        await long_term_memory.add(
            content="Machine learning is a subset of AI",
            metadata={"category": "ai"}
        )

        results = await long_term_memory.search(query="programming", limit=5)
        assert len(results) >= 1

    @pytest.mark.asyncio
    async def test_get_memory(self, long_term_memory):
        """Test getting memory by ID"""
        await long_term_memory.initialize()

        memory_id = await long_term_memory.add(
            content="Test content"
        )

        memory = await long_term_memory.get(memory_id)
        assert memory is not None
        assert memory["content"] == "Test content"

    @pytest.mark.asyncio
    async def test_delete_memory(self, long_term_memory):
        """Test deleting memory"""
        await long_term_memory.initialize()

        memory_id = await long_term_memory.add(content="To be deleted")
        result = await long_term_memory.delete(memory_id)
        assert result is True

        memory = await long_term_memory.get(memory_id)
        assert memory is None

    @pytest.mark.asyncio
    async def test_count(self, long_term_memory):
        """Test memory count"""
        await long_term_memory.initialize()

        for i in range(3):
            await long_term_memory.add(content=f"Memory {i}")

        count = await long_term_memory.count()
        assert count == 3
