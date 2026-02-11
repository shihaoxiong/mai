"""Test Configuration"""

import pytest
import asyncio
from pathlib import Path


@pytest.fixture(scope="session")
def event_loop():
    """Create an instance of the default event loop for the test session."""
    loop = asyncio.get_event_loop_policy().new_event_loop()
    yield loop
    loop.close()


@pytest.fixture
def test_data_dir():
    """Get test data directory"""
    return Path(__file__).parent / "data"


@pytest.fixture
def sample_goal():
    """Sample goal for testing"""
    return "Read a file and summarize its contents"


@pytest.fixture
def sample_context():
    """Sample context for testing"""
    return "Use the file.read tool to access the file"


@pytest.fixture
def sample_plan_dict():
    """Sample plan dictionary"""
    return {
        "goal": "Test goal",
        "description": "Test plan",
        "steps": [
            {
                "step_number": 1,
                "type": "action",
                "description": "Read file",
                "tool": "file.read",
                "parameters": {"path": "test.txt"}
            },
            {
                "step_number": 2,
                "type": "action",
                "description": "Summarize content",
                "tool": None,
                "parameters": {}
            }
        ]
    }
