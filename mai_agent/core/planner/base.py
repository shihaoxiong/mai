"""Planner Base Classes

Abstract base classes and protocols for planning systems.
"""

from abc import ABC, abstractmethod
from typing import Protocol


class PlannerProtocol(Protocol):
    """Protocol for planner implementations"""

    @abstractmethod
    async def create_plan(self, goal: str, context: str) -> "Plan":
        """Create a plan for achieving the goal"""
        pass

    @abstractmethod
    async def replan(
        self,
        original_plan: "Plan",
        feedback: str
    ) -> "Plan":
        """Create a new plan based on feedback"""
        pass

    @abstractmethod
    async def close(self) -> None:
        """Cleanup resources"""
        pass


class BasePlanner(ABC):
    """Abstract base class for planners"""

    @property
    @abstractmethod
    def name(self) -> str:
        """Planner name"""
        pass

    @abstractmethod
    async def create_plan(self, goal: str, context: str) -> "Plan":
        """Create a plan for achieving the goal"""
        pass

    @abstractmethod
    async def replan(
        self,
        original_plan: "Plan",
        feedback: str
    ) -> "Plan":
        """Create a new plan based on feedback"""
        pass

    @abstractmethod
    async def close(self) -> None:
        """Cleanup resources"""
        pass
