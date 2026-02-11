"""LLM-based Critic Implementation

AI-powered decision making for agent execution.
"""

import json
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional

from ...llm import (
    Message,
    MessageRole,
    ChatOptions,
    LLMProvider,
)
try:
    from ...logger import get_logger
except ImportError:
    import sys
    sys.path.insert(0, str(Path(__file__).parent.parent.parent))
    from logger import get_logger
from .schema import (
    CriticResult,
    Decision,
    LLMCriticInput,
)


logger = get_logger("llm_critic")


class LLMCritic:
    """LLM-based critic for intelligent decision making

    Evaluates:
    - Whether to continue, stop, or replan
    - Confidence level in decision
    - Quality of execution
    - Need for replanning
    """

    def __init__(
        self,
        llm_provider: LLMProvider,
        confidence_threshold: float = 0.7,
        temperature: float = 0.1
    ):
        self.llm_provider = llm_provider
        self.confidence_threshold = confidence_threshold
        self.temperature = temperature

    async def evaluate(self, input_data: LLMCriticInput) -> CriticResult:
        """Evaluate execution using LLM"""
        prompt = self._build_prompt(input_data)

        messages = [
            Message(
                role=MessageRole.SYSTEM,
                content=self._get_system_prompt()
            ),
            Message(role=MessageRole.USER, content=prompt)
        ]

        options = ChatOptions(
            temperature=self.temperature,
            max_tokens=1024
        )

        try:
            response = await self.llm_provider.chat(messages, options)
            parsed = self._parse_response(response.content)

            if parsed is None:
                logger.warning(
                    "Failed to parse LLM critic response, using fallback"
                )
                return self._fallback_result(input_data)

            return parsed

        except Exception as e:
            logger.error(
                "LLM critic evaluation failed",
                error=str(e)
            )
            return self._fallback_result(input_data)

    def _get_system_prompt(self) -> str:
        """Get system prompt for critic"""
        return """You are an expert agent supervisor. Your job is to evaluate agent execution and decide whether to:

1. CONTINUE - Execution is going well, proceed to next step
2. STOP - Task is complete or cannot be completed
3. REPLAN - Current approach is not working, need new plan
4. RETRY - Step failed but worth trying again

Consider:
- Is the execution making progress toward the goal?
- Are the observations meaningful?
- Is the agent getting stuck or looping?
- Is the quality of work acceptable?
- Should the approach be changed?

Output a JSON object with your decision, confidence (0.0-1.0), and reasoning."""

    def _build_prompt(self, input_data: LLMCriticInput) -> str:
        """Build evaluation prompt"""
        steps_text = "\n".join([
            f"Step {i+1}: {s.get('description', '')} - {s.get('result', 'pending')}"
            for i, s in enumerate(input_data.completed_steps[-5:])
        ])

        observations_text = "\n".join(
            input_data.observations[-5:]
        )

        prompt = f"""Goal: {input_data.goal}
Current Step: {input_data.current_step + 1} of {input_data.total_steps}

Recent Steps:
{steps_text}

Recent Observations:
{observations_text}

{'Last Execution Result: ' + input_data.execution_result if input_data.execution_result else 'No execution yet'}

Context: {input_data.context or 'None'}

Evaluate the execution and decide what the agent should do next. Consider whether:
- The agent is making progress
- The approach is effective
- A different strategy is needed
- The task can be completed

Return your decision in JSON format with: decision, confidence, reason, and recommendations."""

        return prompt

    def _parse_response(self, response_text: str) -> Optional[CriticResult]:
        """Parse LLM response into CriticResult"""
        try:
            json_str = self._extract_json(response_text)
            data = json.loads(json_str)

            decision_map = {
                "continue": Decision.CONTINUE,
                "stop": Decision.STOP,
                "replan": Decision.REPLAN,
                "retry": Decision.RETRY
            }

            decision = decision_map.get(
                data.get("decision", "continue").lower(),
                Decision.CONTINUE
            )

            return CriticResult(
                decision=decision,
                confidence=data.get("confidence", 0.5),
                reason=data.get("reason", "No reason provided"),
                details=data.get("details", {}),
                warnings=data.get("warnings", [])
            )

        except json.JSONDecodeError:
            logger.error(
                "Failed to parse critic response as JSON",
                response=response_text[:500]
            )
            return None
        except Exception as e:
            logger.error(
                "Error parsing critic response",
                error=str(e)
            )
            return None

    def _extract_json(self, text: str) -> str:
        """Extract JSON from response"""
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

    def _fallback_result(self, input_data: LLMCriticInput) -> CriticResult:
        """Fallback result when LLM fails"""
        if input_data.execution_success:
            if input_data.total_steps <= 0:
                return CriticResult(
                    decision=Decision.CONTINUE,
                    confidence=0.7,
                    reason="Execution successful (fallback)",
                    recommendations=["Continue with next step"]
                )

            if input_data.current_step >= input_data.total_steps:
                return CriticResult(
                    decision=Decision.STOP,
                    confidence=0.8,
                    reason="All steps completed (fallback)",
                    recommendations=["Task appears complete"]
                )

            return CriticResult(
                decision=Decision.CONTINUE,
                confidence=0.7,
                reason="Execution successful (fallback)",
                recommendations=["Continue with next step"]
            )

        return CriticResult(
            decision=Decision.RETRY,
            confidence=0.6,
            reason="Execution failed, retry recommended (fallback)",
            recommendations=["Check error and retry"]
        )

    def get_status(self) -> Dict[str, Any]:
        """Get critic status"""
        return {
            "confidence_threshold": self.confidence_threshold,
            "temperature": self.temperature,
            "provider": self.llm_provider.name,
            "model": self.llm_provider.model
        }
