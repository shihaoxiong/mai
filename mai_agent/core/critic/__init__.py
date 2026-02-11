"""Critic System Module

Provides rule-based and LLM-based critics for decision making.
"""

from .schema import (
    CriticResult,
    Decision,
    RuleCriticInput,
    LLMCriticInput,
    CombinedCriticResult,
)

from .rule_critic import RuleCritic

from .llm_critic import LLMCritic

Critic = LLMCritic


__all__ = [
    "Critic",
    "CriticResult",
    "Decision",
    "RuleCriticInput",
    "LLMCriticInput",
    "CombinedCriticResult",
    "RuleCritic",
    "LLMCritic",
]
