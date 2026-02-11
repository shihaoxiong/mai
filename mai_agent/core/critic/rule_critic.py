"""Rule-based Critic Implementation

Deterministic rules for safety and constraints checking.
"""

import sys
from pathlib import Path
from typing import Any, Dict, List, Optional

try:
    from ...logger import get_logger
except ImportError:
    import sys
    sys.path.insert(0, str(Path(__file__).parent.parent.parent))
    from logger import get_logger
from .schema import (
    CriticResult,
    Decision,
    RuleCriticInput,
)


logger = get_logger("rule_critic")


class RuleCritic:
    """Rule-based critic for safety and constraints checking

    Checks:
    - Maximum steps limit
    - Maximum cost limit
    - Tool whitelist
    - Parameter constraints
    """

    def __init__(
        self,
        max_steps: int = 20,
        max_cost: float = 100.0,
        allowed_tools: Optional[List[str]] = None,
        strict_mode: bool = True
    ):
        self.max_steps = max_steps
        self.max_cost = max_cost
        self.allowed_tools = allowed_tools or []
        self.strict_mode = strict_mode

    def evaluate(self, input_data: RuleCriticInput) -> CriticResult:
        """Evaluate execution against rules"""
        warnings = []
        details = {}

        if not self._check_step_limit(input_data, warnings, details):
            return CriticResult(
                decision=Decision.STOP,
                confidence=1.0,
                reason=f"Maximum steps limit reached: {self.max_steps}",
                details=details,
                warnings=warnings
            )

        if not self._check_cost_limit(input_data, warnings, details):
            return CriticResult(
                decision=Decision.STOP,
                confidence=1.0,
                reason=f"Maximum cost limit exceeded: ${input_data.cost_estimate:.2f}",
                details=details,
                warnings=warnings
            )

        if not self._check_tool_whitelist(input_data, warnings, details):
            return CriticResult(
                decision=Decision.STOP,
                confidence=1.0,
                reason=f"Tool not in whitelist: {input_data.tool_name}",
                details=details,
                warnings=warnings
            )

        if input_data.execution_success:
            if self._check_final_step(input_data):
                return CriticResult(
                    decision=Decision.STOP,
                    confidence=1.0,
                    reason="All steps completed successfully",
                    details=details,
                    warnings=warnings
                )

            return CriticResult(
                decision=Decision.CONTINUE,
                confidence=1.0,
                reason="Execution successful, proceeding to next step",
                details=details,
                warnings=warnings
            )

        else:
            retryable = self._is_retryable_error(input_data)
            if retryable:
                return CriticResult(
                    decision=Decision.RETRY,
                    confidence=0.9,
                    reason="Execution failed but is retryable",
                    details=details,
                    warnings=warnings
                )

            return CriticResult(
                decision=Decision.STOP,
                confidence=0.95,
                reason=f"Execution failed: {input_data.execution_result}",
                details=details,
                warnings=warnings
            )

    def _check_step_limit(
        self,
        input_data: RuleCriticInput,
        warnings: List[str],
        details: Dict[str, Any]
    ) -> bool:
        """Check if step limit is exceeded"""
        remaining_steps = self.max_steps - input_data.current_step
        details["remaining_steps"] = remaining_steps
        details["max_steps"] = self.max_steps

        if input_data.current_step >= self.max_steps:
            warnings.append(f"Step limit reached: {input_data.current_step}/{self.max_steps}")
            return False

        return True

    def _check_cost_limit(
        self,
        input_data: RuleCriticInput,
        warnings: List[str],
        details: Dict[str, Any]
    ) -> bool:
        """Check if cost limit is exceeded"""
        details["current_cost"] = input_data.cost_estimate
        details["max_cost"] = self.max_cost

        if input_data.cost_estimate > self.max_cost:
            warnings.append(
                f"Cost limit exceeded: ${input_data.cost_estimate:.2f} > ${self.max_cost:.2f}"
            )
            return False

        return True

    def _check_tool_whitelist(
        self,
        input_data: RuleCriticInput,
        warnings: List[str],
        details: Dict[str, Any]
    ) -> bool:
        """Check if tool is in whitelist"""
        details["requested_tool"] = input_data.tool_name
        details["allowed_tools"] = self.allowed_tools

        if not input_data.tool_name:
            return True

        if not self.allowed_tools:
            return True

        if input_data.tool_name not in self.allowed_tools:
            warnings.append(f"Tool '{input_data.tool_name}' not in allowed list")
            return False

        return True

    def _check_final_step(self, input_data: RuleCriticInput) -> bool:
        """Check if this is the final step"""
        if input_data.total_steps <= 0:
            return False
        return input_data.current_step >= input_data.total_steps

    def _is_retryable_error(self, input_data: RuleCriticInput) -> bool:
        """Check if error is retryable"""
        if not input_data.execution_result:
            return False

        retryable_patterns = [
            "timeout",
            "connection",
            "temporary",
            "rate limit",
            "server error",
            "try again"
        ]

        result_lower = input_data.execution_result.lower()
        return any(pattern in result_lower for pattern in retryable_patterns)

    def get_status(self) -> Dict[str, Any]:
        """Get current critic status"""
        return {
            "max_steps": self.max_steps,
            "max_cost": self.max_cost,
            "allowed_tools_count": len(self.allowed_tools),
            "strict_mode": self.strict_mode
        }
