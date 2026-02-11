"""MiniMax LLM Provider

Implementation using Anthropic SDK with MiniMax API compatibility.
"""

import os
import asyncio
import json
from typing import Any, Dict, List, Optional, AsyncGenerator
from dataclasses import dataclass
from enum import Enum

import anthropic
from anthropic import Anthropic, AnthropicBedrock, AsyncAnthropic
from anthropic.types import (
    Message,
    TextBlock,
    ToolUseBlock,
    ToolResultBlockParam,
    ThinkingBlock,
    ContentBlock,
    MessageParam,
)

try:
    from ..logger import get_logger
except ImportError:
    from logger import get_logger


logger = get_logger("anthropic_minimax")


class MiniMaxModel(str, Enum):
    """MiniMax model identifiers - Anthropic compatible"""
    M2_1 = "MiniMax-M2.1"
    M2_1_LIGHTNING = "MiniMax-M2.1-lightning"
    M2 = "MiniMax-M2"
    TEXT_01 = "minimax-text-01"


@dataclass
class ChatOptions:
    """Chat completion options"""
    temperature: Optional[float] = None
    max_tokens: Optional[int] = None
    top_p: Optional[float] = None
    stop: Optional[List[str]] = None


@dataclass
class LLMResponse:
    """LLM response"""
    content: str
    usage: Dict[str, int]
    metadata: Dict[str, Any]
    raw_response: Any


class MiniMaxProvider:
    """MiniMax LLM Provider using Anthropic SDK"""

    def __init__(
        self,
        api_key: Optional[str] = None,
        model: str = MiniMaxModel.M2_1.value,
        base_url: Optional[str] = None,
        timeout: float = 120.0,
        **kwargs
    ):
        self.model = model
        self.timeout = timeout

        api_key = api_key or os.environ.get("ANTHROPIC_API_KEY") or os.environ.get("MINIMAX_API_KEY")

        anthropic_base_url = base_url or os.environ.get(
            "ANTHROPIC_BASE_URL",
            "https://api.minimaxi.com/anthropic"
        )

        self._client = Anthropic(
            api_key=api_key,
            base_url=anthropic_base_url,
        )

        self._async_client: Optional[AsyncAnthropic] = None

    @property
    def name(self) -> str:
        return "minimax"

    @property
    def model(self) -> str:
        return self._model

    @model.setter
    def model(self, value: str) -> None:
        self._model = value

    def _convert_messages(
        self,
        messages: List[Any],
        system_prompt: Optional[str] = None
    ) -> tuple[List[MessageParam], Optional[str]]:
        """Convert messages to Anthropic format"""
        anthropic_messages: List[MessageParam] = []
        system = system_prompt

        for msg in messages:
            if hasattr(msg, 'role'):
                role = msg.role
                content = msg.content
            else:
                role = msg.get('role', 'user')
                content = msg.get('content', '')

            if role == 'system':
                if system is None:
                    system = content
                continue

            if isinstance(content, str):
                msg_param: MessageParam = {
                    "role": role,
                    "content": [
                        {
                            "type": "text",
                            "text": content
                        }
                    ]
                }
            else:
                msg_param = {
                    "role": role,
                    "content": content
                }

            anthropic_messages.append(msg_param)

        return anthropic_messages, system

    def _parse_response(self, response: Any) -> LLMResponse:
        """Parse Anthropic SDK response"""
        content_blocks = response.content

        text_content = ""
        tool_calls = []
        thinking = []

        for block in content_blocks:
            if block.type == "text":
                text_content += block.text
            elif block.type == "tool_use":
                tool_calls.append({
                    "id": block.id,
                    "name": block.name,
                    "input": block.input
                })
            elif block.type == "thinking":
                thinking.append(block.thinking)

        usage = response.usage
        metadata = {
            "model": self.model,
            "stop_reason": response.stop_reason,
            "id": response.id,
            "tool_calls": tool_calls,
            "thinking": " ".join(thinking) if thinking else None
        }

        return LLMResponse(
            content=text_content,
            usage={
                "prompt_tokens": usage.input_tokens if hasattr(usage, 'input_tokens') else 0,
                "completion_tokens": usage.output_tokens if hasattr(usage, 'output_tokens') else 0,
                "total_tokens": usage.input_tokens + usage.output_tokens if hasattr(usage, 'input_tokens') else 0
            },
            metadata=metadata,
            raw_response=response
        )

    async def chat(
        self,
        messages: List[Any],
        options: Optional[ChatOptions] = None,
        tools: Optional[List[Dict[str, Any]]] = None,
        system: Optional[str] = None
    ) -> LLMResponse:
        """Send chat completion request"""
        if self._async_client is None:
            self._async_client = AsyncAnthropic(
                api_key=self._client.api_key,
                base_url=str(self._client.base_url) if self._client.base_url else None,
            )

        anthropic_messages, system_prompt = self._convert_messages(messages, system or system_prompt if 'system_prompt' in dir() else system)

        extra_kwargs: Dict[str, Any] = {}

        if options:
            if options.temperature is not None:
                extra_kwargs["temperature"] = options.temperature
            if options.max_tokens is not None:
                extra_kwargs["max_tokens"] = options.max_tokens
            if options.top_p is not None:
                extra_kwargs["top_p"] = options.top_p

        if tools:
            extra_kwargs["tools"] = tools

        logger.debug(
            "Sending chat request to MiniMax (Anthropic SDK)",
            model=self.model,
            message_count=len(messages)
        )

        response = await self._async_client.messages.create(
            model=self.model,
            messages=anthropic_messages,
            system=system_prompt,
            timeout=self.timeout,
            **extra_kwargs
        )

        return self._parse_response(response)

    async def stream_chat(
        self,
        messages: List[Any],
        options: Optional[ChatOptions] = None,
        tools: Optional[List[Dict[str, Any]]] = None,
        system: Optional[str] = None
    ) -> AsyncGenerator[Dict[str, Any], None]:
        """Stream chat completion"""
        if self._async_client is None:
            self._async_client = AsyncAnthropic(
                api_key=self._client.api_key,
                base_url=str(self._client.base_url) if self._client.base_url else None,
            )

        anthropic_messages, system_prompt = self._convert_messages(messages, system)

        extra_kwargs: Dict[str, Any] = {"stream": True}

        if options:
            if options.temperature is not None:
                extra_kwargs["temperature"] = options.temperature
            if options.max_tokens is not None:
                extra_kwargs["max_tokens"] = options.max_tokens
            if options.top_p is not None:
                extra_kwargs["top_p"] = options.top_p

        if tools:
            extra_kwargs["tools"] = tools

        logger.debug(
            "Starting streaming chat request to MiniMax (Anthropic SDK)",
            model=self.model
        )

        async with self._async_client.messages.stream(
            model=self.model,
            messages=anthropic_messages,
            system=system_prompt,
            timeout=self.timeout,
            **extra_kwargs
        ) as stream:
            async for chunk in stream:
                yield {
                    "type": chunk.type if hasattr(chunk, 'type') else str(type(chunk)),
                    "delta": getattr(chunk, 'delta', None),
                    "content": getattr(chunk, 'content', None)
                }

    async def close(self) -> None:
        """Close the async client"""
        if self._async_client:
            await self._async_client.close()
            self._async_client = None


def create_minimax_provider(
    api_key: Optional[str] = None,
    model: str = MiniMaxModel.M2_1.value,
    base_url: Optional[str] = None,
    **kwargs
) -> MiniMaxProvider:
    """Factory function to create MiniMax provider"""
    return MiniMaxProvider(
        api_key=api_key,
        model=model,
        base_url=base_url,
        **kwargs
    )


class MiniMaxEmbeddingProvider:
    """MiniMax text embedding provider (placeholder)"""

    def __init__(
        self,
        api_key: Optional[str] = None,
        model: str = "minimax-emb-01",
        base_url: Optional[str] = None,
        timeout: float = 60.0
    ):
        self.api_key = api_key
        self.model = model
        self.base_url = base_url or "https://api.minimax.chat/v1"
        self.timeout = timeout

    async def embed(self, texts: List[str]) -> List[List[float]]:
        """Generate embeddings for texts"""
        return [[0.0] * 768 for _ in texts]

    async def embed_query(self, query: str) -> List[float]:
        """Generate embedding for a single query"""
        return [0.0] * 768

    async def close(self) -> None:
        """Close the provider"""
        pass
