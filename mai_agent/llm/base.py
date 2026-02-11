"""LLM Base Module

Base classes and interfaces for LLM providers.
"""

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Protocol
from enum import Enum


class MessageRole(str, Enum):
    """Message role enumeration"""
    SYSTEM = "system"
    USER = "user"
    ASSISTANT = "assistant"


@dataclass
class Message:
    """Chat message"""
    role: MessageRole
    content: str
    metadata: Optional[Dict[str, Any]] = None


@dataclass
class LLMResponse:
    """LLM response"""
    content: str
    usage: Dict[str, int]
    metadata: Optional[Dict[str, Any]] = None
    raw_response: Optional[Any] = None


@dataclass
class ChatOptions:
    """Chat completion options"""
    temperature: float = 0.1
    max_tokens: int = 4096
    stop: Optional[List[str]] = None
    top_p: Optional[float] = None
    stream: bool = False


class LLMProvider(ABC):
    """Abstract base class for LLM providers"""

    @property
    @abstractmethod
    def name(self) -> str:
        """Provider name"""
        pass

    @property
    @abstractmethod
    def model(self) -> str:
        """Model identifier"""
        pass

    @abstractmethod
    async def chat(
        self,
        messages: List[Message],
        options: Optional[ChatOptions] = None
    ) -> LLMResponse:
        """Send chat completion request"""
        pass

    @abstractmethod
    async def stream_chat(
        self,
        messages: List[Message],
        options: Optional[ChatOptions] = None
    ):
        """Stream chat completion (async generator)"""
        pass

    @abstractmethod
    async def close(self) -> None:
        """Close the provider and release resources"""
        pass


class LLMProviderRegistry:
    """Registry for LLM providers"""

    _providers: Dict[str, LLMProvider] = {}
    _default_provider: Optional[str] = None

    @classmethod
    def register(cls, name: str, provider: LLMProvider) -> None:
        """Register a provider"""
        cls._providers[name] = provider

    @classmethod
    def get(cls, name: str) -> Optional[LLMProvider]:
        """Get a provider by name"""
        return cls._providers.get(name)

    @classmethod
    def set_default(cls, name: str) -> None:
        """Set default provider"""
        if name in cls._providers:
            cls._default_provider = name

    @classmethod
    def get_default(cls) -> Optional[LLMProvider]:
        """Get default provider"""
        if cls._default_provider:
            return cls._providers.get(cls._default_provider)
        return None

    @classmethod
    def list_providers(cls) -> List[str]:
        """List all registered providers"""
        return list(cls._providers.keys())


def create_llm_provider(
    provider: str,
    api_key: str,
    model: str,
    **kwargs
) -> LLMProvider:
    """Factory function to create LLM provider"""
    from .minimax import MiniMaxProvider

    if provider.lower() == "minimax":
        return MiniMaxProvider(api_key=api_key, model=model, **kwargs)
    else:
        raise ValueError(f"Unknown provider: {provider}")
