"""Planner System Prompts

Templates and prompt strategies for plan generation.
"""

from dataclasses import dataclass
from typing import List, Optional


TOOL_NAME_MAPPING = {
    "bash": "terminal.execute",
    "shell": "terminal.execute",
    "execute": "terminal.execute",
    "terminal": "terminal.execute",
    "run": "terminal.execute",
    "ls": "terminal.ls",
    "cat": "terminal.cat",
    "read": "file.read",
    "write": "file.write",
    "list": "file.ls",
}


@dataclass
class PlannerPromptTemplate:
    """Planner prompt templates"""

    @staticmethod
    def system_prompt(
        available_tools: List[str],
        constraints: Optional[List[str]] = None
    ) -> str:
        """Generate system prompt for planner"""
        tools_str = "\n".join([f"- {tool}" for tool in available_tools])

        constraints_str = ""
        if constraints:
            constraints_str = "\nConstraints:\n" + "\n".join(
                [f"- {c}" for c in constraints]
            )

        return f"""You are an expert planning agent. Your job is to create detailed, actionable plans to achieve user goals.

Available Tools:
{tools_str}

IMPORTANT: Tool names must be EXACT matches from the available tools list above. Common mistakes to avoid:
- DO NOT use "bash", "shell", "execute" - use "terminal.execute"
- DO NOT use "run" - use "terminal.execute"
- DO NOT use "ls" alone - use "terminal.ls"
- DO NOT use "cat" alone - use "terminal.cat"
- DO NOT use "read" alone - use "file.read"
- DO NOT use "write" alone - use "file.write"
- DO NOT use "list" alone - use "file.ls"

{constraints_str}

Planning Guidelines:
1. Break down complex goals into simple, sequential steps
2. Each step should be atomic and verifiable
3. Use the available tools efficiently
4. Consider error handling and edge cases
5. Plan for verification of results

Output Format:
You must output a JSON object with the following structure:
{{
    "plan": {{
        "goal": "The specific goal to achieve",
        "description": "Brief description of the overall plan",
        "steps": [
            {{
                "step_number": 1,
                "type": "action|thought|verification|decision",
                "description": "What to do in this step",
                "tool": "tool name from available tools list or null",
                "parameters": {{"param1": "value1"}},
                "expected_output": "What the step should produce"
            }}
        ]
    }},
    "reasoning": "Explanation of why this plan is appropriate",
    "confidence": 0.9,
    "warnings": [],
    "suggestions": []
}}"""

    @staticmethod
    def create_plan_prompt(
        goal: str,
        context: Optional[str] = None,
        max_steps: int = 10
    ) -> str:
        """Generate prompt for creating a new plan"""
        context_str = f"\nContext:\n{context}" if context else ""

        return f"""Goal: {goal}{context_str}

Create a detailed plan to achieve this goal. Maximum {max_steps} steps.

Think step by step:
1. What is the ultimate goal?
2. What information do I need?
3. What actions should I take?
4. How will I verify success?

Return your plan in the specified JSON format."""

    @staticmethod
    def replan_prompt(
        original_goal: str,
        original_plan_description: str,
        feedback: str,
        current_step: int
    ) -> str:
        """Generate prompt for replanning based on feedback"""
        return f"""Original Goal: {original_goal}
Original Plan: {original_plan_description}

Current Step: {current_step}

Feedback/Issue: {feedback}

Based on this feedback, create a revised plan or adjust the remaining steps.
Explain what went wrong and how the new plan addresses the issue."""

    @staticmethod
    def step_expansion_prompt(
        step_description: str,
        tool_name: str
    ) -> str:
        """Generate prompt for expanding a step into sub-steps"""
        return f"""Expand this step into more detailed sub-steps:

Step: {step_description}
Tool: {tool_name}

Provide a detailed breakdown of how to execute this step effectively."""


class PromptStrategy:
    """Different prompting strategies for planning"""

    ZERO_SHOT = "zero_shot"
    FEW_SHOT = "few_shot"
    CHAIN_OF_THOUGHT = "chain_of_thought"
    REFLECTIVE = "reflective"

    @classmethod
    def get_prompt(
        cls,
        strategy: str,
        goal: str,
        context: Optional[str] = None,
        **kwargs
    ) -> str:
        """Get prompt based on strategy"""
        strategies = {
            cls.ZERO_SHOT: PlannerPromptTemplate.create_plan_prompt(goal, context),
            cls.CHAIN_OF_THOUGHT: PlannerPromptTemplate.create_plan_prompt(
                goal, context
            ) + "\n\nThink through each step carefully before finalizing.",
            cls.REFLECTIVE: PlannerPromptTemplate.create_plan_prompt(
                goal, context
            ) + "\n\nConsider potential issues and how to handle them.",
        }

        return strategies.get(strategy, strategies[cls.ZERO_SHOT])
