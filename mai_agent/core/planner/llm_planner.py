"""LLM-based Planner Implementation

Implementation of LLM-powered planning using MiniMax provider.
"""

import json
import re
from datetime import datetime, timezone
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional
from uuid import uuid4

from ...llm import (
    Message,
    MessageRole,
    ChatOptions,
    LLMProvider,
)

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
    "glob": "file.glob",
    "search": "search.code",
    "grep": "search.grep",
}


def normalize_tool_name(tool: Optional[str], available_tools: List[str]) -> Optional[str]:
    """Normalize tool name to match available tools"""
    if not tool:
        return None

    if tool in available_tools:
        return tool

    normalized = TOOL_NAME_MAPPING.get(tool.lower())
    if normalized and normalized in available_tools:
        return normalized

    for available in available_tools:
        if tool.lower() in available.lower():
            return available

    return None

try:
    from ...config import get_settings
except ImportError:
    import sys
    from pathlib import Path
    sys.path.insert(0, str(Path(__file__).parent.parent.parent))
    from config import get_settings

try:
    from ...logger import get_logger
except ImportError:
    import sys
    sys.path.insert(0, str(Path(__file__).parent.parent.parent))
    from logger import get_logger

from .base import BasePlanner
from .schema import (
    Plan,
    PlanStep,
    PlanStatus,
    StepType,
    StepStatus,
    PlanningRequest,
    PlanningResponse,
)
from .prompts import PlannerPromptTemplate


logger = get_logger("planner")


class LLMPlanner(BasePlanner):
    """LLM-powered planner implementation

    Uses LLM to generate and refine plans based on goals and context.
    """

    def __init__(
        self,
        llm_provider: LLMProvider,
        available_tools: Optional[List[str]] = None,
        constraints: Optional[List[str]] = None,
        max_steps: int = 20,
        temperature: float = 0.1
    ):
        self.llm_provider = llm_provider
        self.available_tools = available_tools or []
        self.constraints = constraints or []
        self.max_steps = max_steps
        self.temperature = temperature

        self._settings = get_settings()
        self._system_prompt: Optional[str] = None

    @property
    def name(self) -> str:
        return f"llm_planner_{self.llm_provider.name}"

    def _build_system_prompt(self) -> str:
        """Build system prompt with available tools"""
        if self._system_prompt is None:
            self._system_prompt = PlannerPromptTemplate.system_prompt(
                available_tools=self.available_tools,
                constraints=self.constraints
            )
        return self._system_prompt

    async def create_plan(
        self,
        goal: str,
        context: Optional[str] = None,
        task_id: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None
    ) -> Plan:
        """Create a new plan for achieving the goal"""
        task_id = task_id or str(uuid4())

        logger.info(
            "Creating plan",
            task_id=task_id,
            goal=goal[:100]
        )

        user_prompt = PlannerPromptTemplate.create_plan_prompt(
            goal=goal,
            context=context,
            max_steps=self.max_steps
        )

        messages = [
            Message(role=MessageRole.SYSTEM, content=self._build_system_prompt()),
            Message(role=MessageRole.USER, content=user_prompt)
        ]

        options = ChatOptions(
            temperature=self.temperature,
            max_tokens=4096
        )

        try:
            response = await self.llm_provider.chat(messages, options)
            response_text = response.content

            logger.debug(
                "Plan generation response",
                task_id=task_id,
                response_length=len(response_text)
            )

            parsed = self._parse_planning_response(response_text, task_id, goal)

            if parsed is None:
                logger.warning(
                    "Failed to parse plan, using fallback",
                    task_id=task_id
                )
                plan = self._create_fallback_plan(task_id, goal)
            else:
                plan = parsed
                plan.metadata.update(metadata or {})

            logger.info(
                "Plan created successfully",
                task_id=task_id,
                plan_id=plan.plan_id,
                steps_count=len(plan.steps)
            )

            return plan

        except Exception as e:
            logger.error(
                "Plan generation failed",
                task_id=task_id,
                error=str(e)
            )
            plan = self._create_fallback_plan(task_id, goal)
            plan.metadata["error"] = str(e)
            return plan

    async def replan(
        self,
        original_plan: Plan,
        feedback: str,
        current_step: int
    ) -> Plan:
        """Create a new plan based on feedback"""
        logger.info(
            "Replanning",
            task_id=original_plan.task_id,
            original_plan_id=original_plan.plan_id,
            current_step=current_step,
            feedback=feedback[:200]
        )

        user_prompt = PlannerPromptTemplate.replan_prompt(
            original_goal=original_plan.goal,
            original_plan_description=original_plan.description or "",
            feedback=feedback,
            current_step=current_step
        )

        messages = [
            Message(role=MessageRole.SYSTEM, content=self._build_system_prompt()),
            Message(role=MessageRole.USER, content=user_prompt)
        ]

        options = ChatOptions(
            temperature=self.temperature,
            max_tokens=4096
        )

        try:
            response = await self.llm_provider.chat(messages, options)
            parsed = self._parse_planning_response(
                response.content,
                original_plan.task_id,
                original_plan.goal
            )

            if parsed:
                parsed.plan_id = str(uuid4())
                logger.info(
                    "Replan created",
                    task_id=original_plan.task_id,
                    new_plan_id=parsed.plan_id
                )
                return parsed

        except Exception as e:
            logger.error(
                "Replanning failed",
                task_id=original_plan.task_id,
                error=str(e)
            )

        return self._create_fallback_plan(
            original_plan.task_id,
            original_plan.goal,
            start_step=current_step
        )

    def _parse_planning_response(
        self,
        response_text: str,
        task_id: str,
        goal: str
    ) -> Optional[Plan]:
        """Parse LLM response into Plan object"""
        try:
            json_str = self._extract_json(response_text)
            data = json.loads(json_str)

            plan_data = data.get("plan", {})
            steps_data = plan_data.get("steps", [])

            steps = []
            for i, step_data in enumerate(steps_data, start=1):
                raw_tool = step_data.get("tool")
                normalized_tool = normalize_tool_name(raw_tool, self.available_tools)

                if raw_tool and not normalized_tool:
                    logger.warning(
                        f"Unknown tool '{raw_tool}' in step {i}, available: {self.available_tools}"
                    )

                step = PlanStep(
                    step_number=i,
                    type=StepType(step_data.get("type", "action")),
                    description=step_data.get("description", ""),
                    tool=normalized_tool,
                    parameters=step_data.get("parameters", {}),
                    expected_output=step_data.get("expected_output")
                )
                steps.append(step)

            plan = Plan(
                task_id=task_id,
                goal=goal,
                description=plan_data.get("description"),
                steps=steps,
                metadata={
                    "reasoning": data.get("reasoning", ""),
                    "confidence": data.get("confidence", 0.0),
                    "warnings": data.get("warnings", []),
                    "suggestions": data.get("suggestions", [])
                }
            )

            return plan

        except json.JSONDecodeError as e:
            logger.error(
                "JSON parsing failed",
                error=str(e),
                response_preview=response_text[:500]
            )
            return None
        except Exception as e:
            logger.error(
                "Plan parsing failed",
                error=str(e)
            )
            return None

    def _extract_json(self, text: str) -> str:
        """Extract JSON from LLM response"""
        text = text.strip()

        if text.startswith("```"):
            lines = text.split("\n")
            if len(lines) >= 2:
                text = "\n".join(lines[1:-1])

        text = text.replace("```json", "").replace("```", "")

        brace_count = 0
        start = None
        end = None

        for i, char in enumerate(text):
            if char == "{":
                if start is None:
                    start = i
                brace_count += 1
            elif char == "}":
                brace_count -= 1
                if brace_count == 0 and start is not None:
                    end = i + 1
                    break

        if start is not None and end is not None:
            return text[start:end]

        return text

    def _create_fallback_plan(
        self,
        task_id: str,
        goal: str,
        start_step: int = 0
    ) -> Plan:
        """Create a simple fallback plan with rule-based tool selection"""
        goal_lower = goal.lower()
        steps = []

        steps.append(PlanStep(
            step_number=len(steps) + 1,
            type=StepType.THOUGHT,
            description=f"Analyze goal: {goal}",
            expected_output="Understanding of the task requirements"
        ))

        if any(keyword in goal_lower for keyword in ["目录", "文件夹", "文件列表", "列出", "list"]):
            if self._has_tool("filesystem.list_directory"):
                steps.append(PlanStep(
                    step_number=len(steps) + 1,
                    type=StepType.ACTION,
                    description="List directory contents",
                    tool="filesystem.list_directory",
                    parameters={"path": "."},
                    expected_output="List of files and directories"
                ))

        elif any(keyword in goal_lower for keyword in ["读取", "查看", "读", "cat", "read", "内容"]):
            if self._has_tool("filesystem.read_file"):
                import re
                path_match = re.search(r'[./\w]+\.\w+', goal)
                file_path = path_match.group() if path_match else "./README.md"
                steps.append(PlanStep(
                    step_number=len(steps) + 1,
                    type=StepType.ACTION,
                    description=f"Read file: {file_path}",
                    tool="filesystem.read_file",
                    parameters={"path": file_path},
                    expected_output="File contents"
                ))

        elif any(keyword in goal_lower for keyword in ["写入", "写", "创建文件", "write"]):
            if self._has_tool("filesystem.write_file"):
                import re
                path_match = re.search(r'[./\w]+\.\w+', goal)
                file_path = path_match.group() if path_match else "./output.txt"
                content_match = re.search(r'内容[：:]\s*(.+)', goal)
                content = content_match.group(1) if content_match else "Hello World"
                steps.append(PlanStep(
                    step_number=len(steps) + 1,
                    type=StepType.ACTION,
                    description=f"Write file: {file_path}",
                    tool="filesystem.write_file",
                    parameters={"path": file_path, "content": content},
                    expected_output="File written successfully"
                ))

        elif any(keyword in goal_lower for keyword in ["搜索", "找", "search", "grep"]):
            if self._has_tool("filesystem.search_files"):
                pattern = goal
                for prefix in ["搜索", "找", "search", "grep"]:
                    pattern = pattern.replace(prefix, "").strip()
                steps.append(PlanStep(
                    step_number=len(steps) + 1,
                    type=StepType.ACTION,
                    description=f"Search for: {pattern}",
                    tool="filesystem.search_files",
                    parameters={"pattern": pattern},
                    expected_output="Search results"
                ))

        elif any(keyword in goal_lower for keyword in ["树", "tree", "结构"]):
            if self._has_tool("filesystem.directory_tree"):
                steps.append(PlanStep(
                    step_number=len(steps) + 1,
                    type=StepType.ACTION,
                    description="Show directory tree structure",
                    tool="filesystem.directory_tree",
                    parameters={"path": "."},
                    expected_output="Directory tree structure"
                ))
            elif self._has_tool("filesystem.list_directory"):
                steps.append(PlanStep(
                    step_number=len(steps) + 1,
                    type=StepType.ACTION,
                    description="List directory contents",
                    tool="filesystem.list_directory",
                    parameters={"path": "."},
                    expected_output="Directory listing"
                ))

        elif any(keyword in goal_lower for keyword in ["说明", "解释", "作用", "功能", "describe", "explain"]):
            if self._has_tool("filesystem.read_file"):
                import re
                path_match = re.search(r'[./\w]+\.md', goal)
                if path_match:
                    file_path = path_match.group()
                else:
                    file_path = "./project_struct.md"
                steps.append(PlanStep(
                    step_number=len(steps) + 1,
                    type=StepType.ACTION,
                    description=f"Read project structure file: {file_path}",
                    tool="filesystem.read_file",
                    parameters={"path": file_path},
                    expected_output="Project structure documentation"
                ))
            else:
                steps.append(PlanStep(
                    step_number=len(steps) + 1,
                    type=StepType.ACTION,
                    description="List root directory contents",
                    tool="filesystem.list_directory",
                    parameters={"path": "."},
                    expected_output="Root directory listing"
                ))

        elif any(keyword in goal_lower for keyword in ["网页", "网站", "打开", "browse", "访问"]):
            if self._has_tool("websearch.puppeteer_navigate"):
                url_match = re.search(r'https?://[^\s]+', goal)
                url = url_match.group() if url_match else "https://example.com"
                steps.append(PlanStep(
                    step_number=len(steps) + 1,
                    type=StepType.ACTION,
                    description=f"Navigate to: {url}",
                    tool="websearch.puppeteer_navigate",
                    parameters={"url": url},
                    expected_output="Page loaded"
                ))

        elif any(keyword in goal_lower for keyword in ["截图", "screenshot", "页面"]):
            if self._has_tool("websearch.puppeteer_screenshot"):
                steps.append(PlanStep(
                    step_number=len(steps) + 1,
                    type=StepType.ACTION,
                    description="Take screenshot of current page",
                    tool="websearch.puppeteer_screenshot",
                    parameters={},
                    expected_output="Screenshot captured"
                ))

        if not steps[1:]:
            steps.append(PlanStep(
                step_number=2,
                type=StepType.ACTION,
                description=f"Manual task: {goal}",
                expected_output="Task completed"
            ))

        plan = Plan(
            task_id=task_id,
            goal=goal,
            description=f"Executing: {goal}",
            steps=steps,
            metadata={
                "reasoning": "Fallback plan with rule-based tool selection",
                "confidence": 0.3,
                "warnings": ["This is a fallback plan - may need manual adjustment"]
            }
        )

        if start_step > 0:
            plan.current_step = start_step

        return plan

    def _has_tool(self, tool_name: str) -> bool:
        """Check if a tool is available"""
        return tool_name in self.available_tools

    async def validate_plan(self, plan: Plan) -> tuple[bool, List[str]]:
        """Validate a plan for common issues"""
        errors = []
        warnings = []

        if not plan.goal:
            errors.append("Plan must have a goal")

        if not plan.steps:
            errors.append("Plan must have at least one step")

        for step in plan.steps:
            if not step.description:
                errors.append(f"Step {step.step_number} must have a description")

            if step.tool and step.tool not in self.available_tools:
                warnings.append(
                    f"Step {step.step_number} uses unknown tool: {step.tool}"
                )

        if len(plan.steps) > self.max_steps:
            warnings.append(
                f"Plan has {len(plan.steps)} steps, exceeding maximum of {self.max_steps}"
            )

        return len(errors) == 0, errors + warnings

    async def estimate_plan_complexity(self, plan: Plan) -> Dict[str, Any]:
        """Estimate plan complexity metrics"""
        return {
            "total_steps": len(plan.steps),
            "tool_count": len(set(s.tool for s in plan.steps if s.tool)),
            "action_steps": len([s for s in plan.steps if s.type == StepType.ACTION]),
            "thought_steps": len([s for s in plan.steps if s.type == StepType.THOUGHT]),
            "verification_steps": len([
                s for s in plan.steps if s.type == StepType.VERIFICATION
            ]),
            "estimated_duration_steps": sum(
                1 for s in plan.steps if s.type in [StepType.ACTION, StepType.VERIFICATION]
            )
        }

    async def close(self) -> None:
        """Close the planner and release resources"""
        await self.llm_provider.close()
        logger.info("Planner closed", name=self.name)


class SimplePlanner(BasePlanner):
    """Simple rule-based planner for basic tasks"""

    def __init__(self, available_tools: Optional[List[str]] = None):
        self.available_tools = available_tools or []

    @property
    def name(self) -> str:
        return "simple_planner"

    async def create_plan(self, goal: str, context: str) -> Plan:
        """Create a simple two-step plan"""
        plan = Plan(
            task_id=str(uuid4()),
            goal=goal,
            description="Simple execution plan",
            steps=[
                PlanStep(
                    step_number=1,
                    type=StepType.ACTION,
                    description=f"Analyze and understand: {goal}",
                    expected_output="Clear understanding of the task"
                ),
                PlanStep(
                    step_number=2,
                    type=StepType.ACTION,
                    description="Execute the task",
                    expected_output="Task completed successfully"
                )
            ]
        )
        return plan

    async def replan(
        self,
        original_plan: Plan,
        feedback: str
    ) -> Plan:
        """Create a revised plan"""
        new_plan = await self.create_plan(original_plan.goal, feedback)
        new_plan.plan_id = str(uuid4())
        new_plan.metadata["replan_reason"] = feedback
        return new_plan

    async def close(self) -> None:
        pass
