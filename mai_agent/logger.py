"""Logging System Module

Provides structured JSON logging for the Agent system.
"""

import json
import logging
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Optional

import structlog
from pythonjsonlogger import core
from structlog.processors import (
    TimeStamper,
    add_log_level,
    format_exc_info,
)

try:
    from .config import Settings, get_settings
except ImportError:
    from config import Settings, get_settings


def get_log_level(level: str) -> int:
    """Convert string log level to logging constant"""
    return getattr(logging, level.upper(), logging.INFO)


class CustomJsonFormatter(core.BaseJsonFormatter):
    """Custom JSON formatter with additional fields"""

    def format(self, record: logging.LogRecord) -> str:
        """Format log record as JSON"""
        log_data = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "level": record.levelname,
            "name": record.name,
            "message": record.getMessage(),
            "module": record.module,
            "function": record.funcName,
            "line": record.lineno,
        }

        if record.exc_info:
            log_data["exception"] = self.formatException(record.exc_info)

        if hasattr(record, "extra_data") and record.extra_data:
            log_data["extra"] = record.extra_data

        return json.dumps(log_data, ensure_ascii=False)


class LoggerManager:
    """Centralized Logger Manager"""

    _instance: Optional["LoggerManager"] = None
    _initialized: bool = False

    def __new__(cls) -> "LoggerManager":
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self):
        if not LoggerManager._initialized:
            self._loggers: Dict[str, structlog.BoundLogger] = {}
            self._settings: Optional[Settings] = None
            LoggerManager._initialized = True

    def initialize(self, settings: Optional[Settings] = None) -> None:
        """Initialize the logging system"""
        self._settings = settings or get_settings()

        if self._settings.logging.format == "json":
            self._setup_json_logging()
        else:
            self._setup_plain_logging()

        self._setup_structlog()

    def _setup_json_logging(self) -> None:
        """Setup JSON formatted logging"""
        root_logger = logging.getLogger()
        root_logger.setLevel(get_log_level(self._settings.logging.level))

        handler = logging.StreamHandler(sys.stdout)
        handler.setFormatter(CustomJsonFormatter())
        handler.setLevel(get_log_level(self._settings.logging.level))

        if not root_logger.handlers:
            root_logger.addHandler(handler)

    def _setup_plain_logging(self) -> None:
        """Setup plain text formatted logging"""
        root_logger = logging.getLogger()
        root_logger.setLevel(get_log_level(self._settings.logging.level))

        handler = logging.StreamHandler(sys.stdout)
        formatter = logging.Formatter(
            fmt="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
            datefmt="%Y-%m-%d %H:%M:%S"
        )
        handler.setFormatter(formatter)
        handler.setLevel(get_log_level(self._settings.logging.level))

        if not root_logger.handlers:
            root_logger.addHandler(handler)

    def _setup_structlog(self) -> None:
        """Configure structlog processors"""
        structlog.configure(
            processors=[
                add_log_level,
                TimeStamper(fmt="iso"),
                format_exc_info,
                structlog.processors.JSONRenderer(ensure_ascii=False),
            ],
            wrapper_class=structlog.make_filtering_bound_logger(
                get_log_level(self._settings.logging.level)
            ),
            context_class=dict,
            logger_factory=structlog.PrintLoggerFactory(),
            cache_logger_on_first_use=True,
        )

    def get_logger(self, name: str) -> structlog.BoundLogger:
        """Get a structured logger instance"""
        if name not in self._loggers:
            self._loggers[name] = structlog.get_logger(name)
        return self._loggers[name]

    def bind_context(self, logger: structlog.BoundLogger, **kwargs: Any) -> structlog.BoundLogger:
        """Bind context to logger"""
        return logger.bind(**kwargs)


def get_logger(name: str) -> structlog.BoundLogger:
    """Get a structured logger instance"""
    manager = LoggerManager()
    return manager.get_logger(name)


def init_logging(settings: Optional[Settings] = None) -> None:
    """Initialize the logging system"""
    manager = LoggerManager()
    manager.initialize(settings)


class AgentLogger:
    """Agent-specific logger with contextual information"""

    def __init__(self, agent_name: str = "mai-agent", task_id: Optional[str] = None):
        self._logger = get_logger("mai_agent")
        self._agent_name = agent_name
        self._task_id = task_id

    def with_task(self, task_id: str) -> "AgentLogger":
        """Create new logger with task context"""
        return AgentLogger(self._agent_name, task_id)

    def info(self, message: str, **kwargs: Any) -> None:
        """Log info message"""
        self._logger.info(
            message,
            agent=self._agent_name,
            task_id=self._task_id,
            **kwargs
        )

    def error(self, message: str, **kwargs: Any) -> None:
        """Log error message"""
        self._logger.error(
            message,
            agent=self._agent_name,
            task_id=self._task_id,
            **kwargs
        )

    def warning(self, message: str, **kwargs: Any) -> None:
        """Log warning message"""
        self._logger.warning(
            message,
            agent=self._agent_name,
            task_id=self._task_id,
            **kwargs
        )

    def debug(self, message: str, **kwargs: Any) -> None:
        """Log debug message"""
        self._logger.debug(
            message,
            agent=self._agent_name,
            task_id=self._task_id,
            **kwargs
        )

    def critical(self, message: str, **kwargs: Any) -> None:
        """Log critical message"""
        self._logger.critical(
            message,
            agent=self._agent_name,
            task_id=self._task_id,
            **kwargs
        )


def get_agent_logger(agent_name: str = "mai-agent", task_id: Optional[str] = None) -> AgentLogger:
    """Get an agent-specific logger"""
    return AgentLogger(agent_name, task_id)
