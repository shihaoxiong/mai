"""Observation Parser Implementation

Parses tool outputs and classifies observations.
"""

import json
import re
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

try:
    from ...logger import get_logger
except ImportError:
    import sys
    sys.path.insert(0, str(Path(__file__).parent.parent.parent))
    from logger import get_logger
from .schema import Observation, ObservationType, ErrorCategory, ErrorDetails


logger = get_logger("observation")


class ObservationParser:
    """Parses and classifies observations from tool outputs"""

    ERROR_PATTERNS = {
        ErrorCategory.VALIDATION: [
            r"invalid\s+(?:input|parameter|argument|option)",
            r"missing\s+(?:required|parameter)",
            r"validation\s+failed",
            r"constraint\s+violation",
            r"does not match expected",
            r"out of range",
        ],
        ErrorCategory.TIMEOUT: [
            r"timed?out",
            r"took too long",
            r"deadline exceeded",
            r"connection timed out",
            r"request timeout",
        ],
        ErrorCategory.PERMISSION: [
            r"permission denied",
            r"access denied",
            r"not authorized",
            r"forbidden",
            r"insufficient privileges",
            r"read-only",
        ],
        ErrorCategory.NOT_FOUND: [
            r"not found",
            r"does not exist",
            r"no such file",
            r"no such directory",
            r"resource not found",
            r"undefined",
        ],
        ErrorCategory.RESOURCE: [
            r"out of memory",
            r"disk space",
            r"too many",
            r"rate limit",
            r"quota exceeded",
            r"resource (?:unavailable|exhausted)",
        ],
        ErrorCategory.RETRYABLE: [
            r"try again",
            r"temporary failure",
            r"server error",
            r"service unavailable",
            r"connection refused",
            r"network error",
            r"please retry",
        ]
    }

    SUCCESS_INDICATORS = [
        "completed successfully",
        "done",
        "finished",
        "executed",
        "created",
        "updated",
        "deleted",
        "written",
        "success",
        "ok",
        "passed",
        "verified",
    ]

    def parse(
        self,
        step_id: str,
        output: str,
        success: bool,
        error: Optional[str] = None
    ) -> Observation:
        """Parse tool output into observation"""
        content = output or error or ""

        if success:
            observation_type = self._classify_success(output)
        else:
            observation_type, error_category = self._classify_error(error or content)
            retryable = error_category in [ErrorCategory.TIMEOUT, ErrorCategory.RETRYABLE]
            return Observation(
                step_id=step_id,
                observation_type=observation_type,
                content=content,
                error_category=error_category,
                retryable=retryable
            )

        return Observation(
            step_id=step_id,
            observation_type=observation_type,
            content=content
        )

    def _classify_success(self, output: str) -> ObservationType:
        """Classify success type from output"""
        output_lower = output.lower()

        if any(word in output_lower for word in ["warning", "caution", "notice"]):
            return ObservationType.WARNING

        if any(word in output_lower for word in ["info", "information", "note"]):
            return ObservationType.INFO

        return ObservationType.SUCCESS

    def _classify_error(
        self,
        error: str
    ) -> Tuple[ObservationType, ErrorCategory]:
        """Classify error type from error message"""
        error_lower = error.lower()

        for category, patterns in self.ERROR_PATTERNS.items():
            for pattern in patterns:
                if re.search(pattern, error_lower):
                    return ObservationType.ERROR, category

        return ObservationType.ERROR, ErrorCategory.UNKNOWN

    def extract_json(self, output: str) -> Optional[Dict[str, Any]]:
        """Extract JSON from output"""
        output = output.strip()

        if output.startswith("{"):
            try:
                return json.loads(output)
            except json.JSONDecodeError:
                pass

        json_match = re.search(r'\{[^{}]*\}', output)
        if json_match:
            try:
                return json.loads(json_match.group())
            except json.JSONDecodeError:
                pass

        return None

    def extract_code_blocks(self, output: str) -> List[str]:
        """Extract code blocks from output"""
        pattern = r'```(\w*)\n([\s\S]*?)```'
        matches = re.findall(pattern, output, re.DOTALL)

        return [code for lang, code in matches]

    def extract_file_paths(self, output: str) -> List[str]:
        """Extract file paths from output"""
        patterns = [
            r'["\']((?:[^"\'/\n]*/)*[^"\'/\n]+)["\']',
            r'(?<=: )\/?(?:[^\s\n]+)',
        ]

        paths = []
        for pattern in patterns:
            matches = re.findall(pattern, output)
            paths.extend([m for m in matches if self._looks_like_path(m)])

        return list(set(paths))

    def _looks_like_path(self, text: str) -> bool:
        """Check if text looks like a file path"""
        path_indicators = ["/", ".", "\\", ":"]
        return any(ind in text for ind in path_indicators)

    def summarize(self, observation: Observation) -> str:
        """Generate summary of observation"""
        if observation.observation_type == ObservationType.SUCCESS:
            return f"Step completed successfully"

        if observation.observation_type == ObservationType.WARNING:
            return f"Warning: {observation.content[:100]}"

        if observation.observation_type == ObservationType.ERROR:
            category = observation.error_category.value if observation.error_category else "unknown"
            return f"Error ({category}): {observation.content[:100]}"

        return observation.content[:100]


class ErrorClassifier:
    """Classifies and provides error details"""

    def __init__(self):
        self._suggestions = {
            ErrorCategory.VALIDATION: "Check input parameters and constraints",
            ErrorCategory.TIMEOUT: "Consider increasing timeout or simplifying the operation",
            ErrorCategory.PERMISSION: "Verify access rights and permissions",
            ErrorCategory.NOT_FOUND: "Check that the resource exists",
            ErrorCategory.RESOURCE: "Free up resources or reduce load",
            ErrorCategory.RETRYABLE: "Retry the operation",
            ErrorCategory.UNKNOWN: "Review the error and try a different approach",
        }

    def classify(self, error: str, step_id: Optional[str] = None) -> ErrorDetails:
        """Classify error and provide details"""
        error_lower = error.lower()

        category = ErrorCategory.UNKNOWN
        for cat, patterns in ObservationParser.ERROR_PATTERNS.items():
            for pattern in patterns:
                if re.search(pattern, error_lower):
                    category = cat
                    break

        return ErrorDetails(
            error_type=type(error).__name__ if hasattr(error, "__class__") else "Error",
            error_message=error,
            error_category=category,
            suggestion=self._suggestions.get(category),
            related_step=step_id,
            raw_error=str(error)
        )

    def get_recovery_suggestion(self, error: ErrorDetails) -> str:
        """Get recovery suggestion for error"""
        suggestions = []

        if error.suggestion:
            suggestions.append(error.suggestion)

        if error.error_category == ErrorCategory.VALIDATION:
            suggestions.append("Check parameter types and required fields")
            suggestions.append("Review input format")

        elif error.error_category == ErrorCategory.TIMEOUT:
            suggestions.append("Try with smaller input or operation")
            suggestions.append("Check network connectivity")

        elif error.error_category == ErrorCategory.PERMISSION:
            suggestions.append("Verify file/directory permissions")
            suggestions.append("Check API key or authentication")

        elif error.error_category == ErrorCategory.NOT_FOUND:
            suggestions.append("Verify file path or resource identifier")
            suggestions.append("Create the resource if applicable")

        elif error.error_category == ErrorCategory.RESOURCE:
            suggestions.append("Free up disk space or memory")
            suggestions.append("Wait and retry later")

        return "\n".join(suggestions) if suggestions else error.suggestion or "No suggestion available"
