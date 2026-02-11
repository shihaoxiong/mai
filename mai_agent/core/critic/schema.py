"""Critic Schema Definitions

Models for decision making and criticism.
"""

from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Dict, List, Optional


class Decision(str, Enum):
    """Critic decision enumeration"""
    CONTINUE = "continue"
    STOP = "stop"
    REPLAN = "replan"
    RETRY = "retry"


@dataclass
class CriticResult:
    """Result from a critic"""
    decision: Decision
    confidence: float
    reason: str
    details: Dict[str, Any] = field(default_factory=dict)
    warnings: List[str] = field(default_factory=list)
    recommendations: List[str] = field(default_factory=list)


@dataclass
class RuleCriticInput:
    """Input for rule-based critic"""
    current_step: int
    total_steps: int
    tool_name: Optional[str]
    tool_parameters: Dict[str, Any]
    execution_result: Optional[str]
    execution_success: bool
    cost_estimate: float
    allowed_tools: List[str]
    max_steps: int
    max_cost: float


@dataclass
class LLMCriticInput:
    """Input for LLM-based critic"""
    goal: str
    current_step: int
    total_steps: int
    observations: List[str]
    completed_steps: List[Dict[str, Any]]
    execution_result: Optional[str]
    execution_success: bool
    context: Optional[str] = None


@dataclass
class CombinedCriticResult:
    """Combined result from all critics"""
    final_decision: Decision
    rule_result: Optional[CriticResult]
    llm_result: Optional[CriticResult]
    confidence: float
    reasoning: str
    should_replan: bool
    should_stop: bool
    recommendations: List[str] = field(default_factory=list)
