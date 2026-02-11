"""Observation Schema Definitions

Models for tool output parsing and error classification.
"""

from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Dict, List, Optional


class ObservationType(str, Enum):
    """Observation type enumeration"""
    SUCCESS = "success"
    FAILURE = "failure"
    ERROR = "error"
    WARNING = "warning"
    INFO = "info"


class ErrorCategory(str, Enum):
    """Error category enumeration"""
    VALIDATION = "validation"
    TIMEOUT = "timeout"
    PERMISSION = "permission"
    NOT_FOUND = "not_found"
    RESOURCE = "resource"
    UNKNOWN = "unknown"
    RETRYABLE = "retryable"


@dataclass
class Observation:
    """Observation from tool execution"""
    step_id: str
    observation_type: ObservationType
    content: str
    timestamp: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    error_category: Optional[ErrorCategory] = None
    retryable: bool = False
    raw_output_ref: Optional[str] = None
    metadata: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "step_id": self.step_id,
            "observation_type": self.observation_type.value,
            "content": self.content,
            "timestamp": self.timestamp.isoformat(),
            "error_category": self.error_category.value if self.error_category else None,
            "retryable": self.retryable,
            "raw_output_ref": self.raw_output_ref,
            "metadata": self.metadata
        }


@dataclass
class ErrorDetails:
    """Detailed error information"""
    error_type: str
    error_message: str
    error_category: ErrorCategory
    suggestion: Optional[str] = None
    related_step: Optional[str] = None
    raw_error: Optional[str] = None
