"""Test for Planner Module

Tests for plan generation and schema validation.
"""

import pytest
import asyncio
from unittest.mock import AsyncMock, MagicMock

from mai_agent.core.planner import (
    Plan,
    PlanStep,
    PlanStatus,
    StepType,
    StepStatus,
    LLMPlanner,
    SimplePlanner,
)
from mai_agent.core.planner.schema import PlanningRequest


class TestPlanSchema:
    """Tests for plan schema"""

    def test_create_plan(self):
        """Test creating a basic plan"""
        plan = Plan(
            task_id="test-task-1",
            goal="Test goal",
            description="Test plan"
        )

        assert plan.task_id == "test-task-1"
        assert plan.goal == "Test goal"
        assert plan.status == PlanStatus.PENDING
        assert plan.current_step == 0

    def test_add_step(self):
        """Test adding steps to plan"""
        plan = Plan(task_id="test", goal="Test")
        step = PlanStep(
            step_number=1,
            description="Step 1",
            tool="test_tool",
            parameters={"arg": "value"}
        )

        plan.add_step(step)

        assert len(plan.steps) == 1
        assert plan.steps[0].step_number == 1

    def test_get_next_step(self):
        """Test getting next step"""
        plan = Plan(task_id="test", goal="Test")
        plan.add_step(PlanStep(step_number=1, description="Step 1"))
        plan.add_step(PlanStep(step_number=2, description="Step 2"))

        next_step = plan.get_next_step()
        assert next_step is not None
        assert next_step.step_number == 1

    def test_mark_step_completed(self):
        """Test marking step as completed"""
        step = PlanStep(step_number=1, description="Test step")
        step.mark_completed("Result")

        assert step.status == StepStatus.COMPLETED
        assert step.result == "Result"
        assert step.completed_at is not None

    def test_mark_step_failed(self):
        """Test marking step as failed"""
        step = PlanStep(step_number=1, description="Test step")
        step.mark_failed("Error message")

        assert step.status == StepStatus.FAILED
        assert step.error == "Error message"

    def test_plan_to_dict(self):
        """Test converting plan to dictionary"""
        plan = Plan(
            task_id="test",
            goal="Test goal",
            steps=[
                PlanStep(step_number=1, description="Step 1")
            ]
        )

        plan_dict = plan.to_dict()

        assert plan_dict["task_id"] == "test"
        assert plan_dict["goal"] == "Test goal"
        assert len(plan_dict["steps"]) == 1

    def test_plan_from_dict(self):
        """Test creating plan from dictionary"""
        data = {
            "plan_id": "plan-123",
            "task_id": "task-123",
            "goal": "Test goal",
            "description": "A test plan",
            "steps": [
                {
                    "step_id": "step-1",
                    "step_number": 1,
                    "type": "action",
                    "description": "Step 1",
                    "tool": "test_tool",
                    "parameters": {},
                    "status": "pending"
                }
            ],
            "status": "pending",
            "current_step": 0
        }

        plan = Plan.from_dict(data)

        assert plan.task_id == "task-123"
        assert plan.goal == "Test goal"
        assert len(plan.steps) == 1


class TestStepTypes:
    """Tests for step type operations"""

    def test_step_types(self):
        """Test step type enumeration"""
        assert StepType.ACTION == "action"
        assert StepType.THOUGHT == "thought"
        assert StepType.VERIFICATION == "verification"
        assert StepType.DECISION == "decision"

    def test_step_statuses(self):
        """Test step status enumeration"""
        assert StepStatus.PENDING == "pending"
        assert StepStatus.RUNNING == "running"
        assert StepStatus.COMPLETED == "completed"
        assert StepStatus.FAILED == "failed"


class TestSimplePlanner:
    """Tests for simple planner"""

    @pytest.fixture
    def planner(self):
        """Create simple planner"""
        return SimplePlanner(available_tools=["test_tool"])

    @pytest.mark.asyncio
    async def test_create_plan(self, planner):
        """Test creating a simple plan"""
        plan = await planner.create_plan(
            goal="Test goal",
            context="Test context"
        )

        assert plan.goal == "Test goal"
        assert len(plan.steps) == 2
        assert plan.steps[0].type == StepType.ACTION

    @pytest.mark.asyncio
    async def test_replan(self, planner):
        """Test replanning"""
        original = Plan(
            task_id="test",
            goal="Original goal"
        )

        new_plan = await planner.replan(
            original_plan=original,
            feedback="Need to change approach"
        )

        assert new_plan.goal == "Original goal"
        assert new_plan.plan_id != original.plan_id
        assert "replan_reason" in new_plan.metadata


class TestLLMPlanner:
    """Tests for LLM planner"""

    @pytest.fixture
    def mock_llm_provider(self):
        """Create mock LLM provider"""
        provider = AsyncMock()
        provider.name = "test"
        provider.model = "test-model"

        response = MagicMock()
        response.content = '''
{
    "plan": {
        "goal": "Test goal",
        "description": "Test plan",
        "steps": [
            {
                "step_number": 1,
                "type": "action",
                "description": "Execute test",
                "tool": "test_tool",
                "parameters": {},
                "expected_output": "Success"
            }
        ]
    },
    "reasoning": "Simple test plan",
    "confidence": 0.9,
    "warnings": [],
    "suggestions": []
}
'''
        provider.chat.return_value = response
        return provider

    @pytest.mark.asyncio
    async def test_create_plan_with_mock(self, mock_llm_provider):
        """Test creating plan with mock LLM"""
        planner = LLMPlanner(
            llm_provider=mock_llm_provider,
            available_tools=["test_tool"],
            temperature=0.1
        )

        plan = await planner.create_plan(
            goal="Test goal",
            context="Test context",
            task_id="test-task"
        )

        assert plan.goal == "Test goal"
        assert len(plan.steps) >= 1

    @pytest.mark.asyncio
    async def test_fallback_plan_on_error(self, mock_llm_provider):
        """Test fallback plan when LLM fails"""
        mock_llm_provider.chat.side_effect = Exception("LLM Error")

        planner = LLMPlanner(
            llm_provider=mock_llm_provider,
            temperature=0.1
        )

        plan = await planner.create_plan(
            goal="Test goal",
            task_id="test"
        )

        assert plan is not None
        assert "error" in plan.metadata

    def test_extract_json(self, mock_llm_provider):
        """Test JSON extraction from response"""
        planner = LLMPlanner(llm_provider=mock_llm_provider)

        json_str = planner._extract_json('''
Some text before
```json
{"key": "value"}
```
Some text after
''')

        assert '{"key": "value"}' in json_str or 'key' in json_str

    @pytest.mark.asyncio
    async def test_validate_plan(self, mock_llm_provider):
        """Test plan validation"""
        planner = LLMPlanner(llm_provider=mock_llm_provider)

        plan = Plan(
            task_id="test",
            goal="Test",
            steps=[PlanStep(step_number=1, description="Step 1")]
        )

        valid, errors = await planner.validate_plan(plan)

        assert valid is True
        assert len(errors) == 0

    @pytest.mark.asyncio
    async def test_validate_empty_plan(self, mock_llm_provider):
        """Test validation of plan without steps"""
        planner = LLMPlanner(llm_provider=mock_llm_provider)

        plan = Plan(task_id="test", goal="Test", steps=[])

        valid, errors = await planner.validate_plan(plan)

        assert valid is False
        assert any("step" in e.lower() for e in errors)

    @pytest.mark.asyncio
    async def test_estimate_complexity(self, mock_llm_provider):
        """Test plan complexity estimation"""
        planner = LLMPlanner(llm_provider=mock_llm_provider)

        plan = Plan(
            task_id="test",
            goal="Test",
            steps=[
                PlanStep(step_number=1, description="Step 1", type=StepType.THOUGHT),
                PlanStep(step_number=2, description="Step 2", type=StepType.ACTION),
                PlanStep(step_number=3, description="Step 3", type=StepType.VERIFICATION),
            ]
        )

        complexity = await planner.estimate_plan_complexity(plan)

        assert "total_steps" in complexity
        assert complexity["total_steps"] == 3
