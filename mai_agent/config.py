"""Configuration Management Module

Provides centralized configuration using Pydantic Settings.
"""

import os
from pathlib import Path
from typing import Any, Dict, List, Optional
from functools import lru_cache

import yaml
from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class LLMSettings(BaseSettings):
    """LLM Configuration"""
    provider: str = "minimax"
    model: str = "minimax-text-01"
    api_key: str = Field(default="${MINIMAX_API_KEY}")
    temperature: float = 0.1
    max_tokens: int = 4096
    request_timeout: int = 60

    model_config = SettingsConfigDict(env_prefix="LLM_")


class AgentSettings(BaseSettings):
    """Agent Core Configuration"""
    name: str = "mai-agent"
    version: str = "1.0.0"
    max_steps: int = 20
    execution_timeout: int = 300
    retry_on_failure: bool = True
    max_retries: int = 3

    model_config = SettingsConfigDict(env_prefix="AGENT_")


class MemorySettings(BaseSettings):
    """Memory System Configuration"""
    working_memory: Dict[str, bool] = Field(default_factory=lambda: {"enabled": True})
    episodic_memory: Dict[str, Any] = Field(default_factory=lambda: {
        "enabled": True,
        "storage": "sqlite",
        "path": "./data/memory.db"
    })
    long_term_memory: Dict[str, Any] = Field(default_factory=lambda: {
        "enabled": True,
        "storage": "chroma",
        "path": "./data/chroma",
        "collection_name": "agent_memories"
    })

    model_config = SettingsConfigDict(env_prefix="MEMORY_")


class MCPTerminalSettings(BaseSettings):
    """MCP Terminal Tool Configuration"""
    enabled: bool = True
    allowed_commands: List[str] = Field(default_factory=lambda: [
        "ls", "cat", "echo", "grep", "find", "head", "tail",
        "wc", "mkdir", "touch", "python", "python3"
    ])

    model_config = SettingsConfigDict(env_prefix="MCP_TERMINAL_")


class MCPFileSettings(BaseSettings):
    """MCP File Tool Configuration"""
    enabled: bool = True
    allowed_paths: List[str] = Field(default_factory=lambda: ["./", "./data"])
    max_file_size: int = 10485760

    model_config = SettingsConfigDict(env_prefix="MCP_FILE_")


class MCPSettings(BaseSettings):
    """MCP Configuration"""
    enabled: bool = True
    tools: Dict[str, Any] = Field(default_factory=dict)

    @field_validator("tools", mode="before")
    @classmethod
    def build_tools_dict(cls, v: Any) -> Dict[str, Any]:
        if isinstance(v, dict):
            return v
        return {
            "terminal": MCPTerminalSettings().model_dump(),
            "file": MCPFileSettings().model_dump(),
            "websearch": {"enabled": False}
        }

    model_config = SettingsConfigDict(env_prefix="MCP_")


class RuleCriticSettings(BaseSettings):
    """Rule-based Critic Configuration"""
    enabled: bool = True
    max_steps: int = 20
    max_cost: float = 100.0
    allowed_tools: List[str] = Field(default_factory=lambda: ["terminal", "file"])

    model_config = SettingsConfigDict(env_prefix="CRITIC_RULE_")


class LLMCriticSettings(BaseSettings):
    """LLM-based Critic Configuration"""
    enabled: bool = True
    confidence_threshold: float = 0.7

    model_config = SettingsConfigDict(env_prefix="CRITIC_LLM_")


class CriticSettings(BaseSettings):
    """Critic System Configuration"""
    rule_critic: Dict[str, Any] = Field(default_factory=lambda: {
        "enabled": True,
        "max_steps": 20,
        "max_cost": 100.0,
        "allowed_tools": ["terminal", "file"]
    })
    llm_critic: Dict[str, Any] = Field(default_factory=lambda: {
        "enabled": True,
        "confidence_threshold": 0.7
    })

    model_config = SettingsConfigDict(env_prefix="CRITIC_")


class AuditSettings(BaseSettings):
    """Audit System Configuration"""
    enabled: bool = True
    storage: str = "json"
    path: str = "./data/audit"
    log_level: str = "INFO"

    model_config = SettingsConfigDict(env_prefix="AUDIT_")


class StorageSettings(BaseSettings):
    """Storage Paths Configuration"""
    data_dir: str = "./data"
    chroma_dir: str = "./data/chroma"
    audit_dir: str = "./data/audit"

    model_config = SettingsConfigDict(env_prefix="STORAGE_")


class LoggingSettings(BaseSettings):
    """Logging Configuration"""
    level: str = "INFO"
    format: str = "json"
    include_timestamp: bool = True

    model_config = SettingsConfigDict(env_prefix="LOG_")


class Settings(BaseSettings):
    """Main Application Settings"""
    agent: AgentSettings = Field(default_factory=AgentSettings)
    llm: LLMSettings = Field(default_factory=LLMSettings)
    memory: MemorySettings = Field(default_factory=MemorySettings)
    mcp: MCPSettings = Field(default_factory=MCPSettings)
    critic: CriticSettings = Field(default_factory=CriticSettings)
    audit: AuditSettings = Field(default_factory=AuditSettings)
    storage: StorageSettings = Field(default_factory=StorageSettings)
    logging: LoggingSettings = Field(default_factory=LoggingSettings)

    @classmethod
    def from_yaml(cls, config_path: str = "configs/settings.yaml") -> "Settings":
        """Load settings from YAML configuration file"""
        path = Path(config_path)
        if not path.exists():
            return cls()

        with open(path, "r") as f:
            yaml_config = yaml.safe_load(f) or {}

        return cls(**yaml_config)


@lru_cache()
def get_settings(config_path: str = "configs/settings.yaml") -> Settings:
    """Get cached settings instance"""
    return Settings.from_yaml(config_path)
