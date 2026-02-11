"""Agent State Machine with Session Support

Core agent orchestration with session management and goal inference.
"""

import asyncio
import sys
from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from logger import get_logger
from config import get_settings
from llm import create_llm_provider, Message, MessageRole

from ..memory.session_memory import (
    InMemoryContext,
    SessionEpisodicMemory,
    ContextManager,
)
from ..planner import Plan, Planner, LLMPlanner
from ..executor import Executor, ExecutionResult
from ..critic import CriticResult, Decision, RuleCritic, LLMCritic, RuleCriticInput, LLMCriticInput
from ..observation import ObservationParser
from ..mcp import MCPServerManager
from ..audit import AuditLogger, AuditEventType
from ..session import SessionManager, SessionContext, GoalInferencer, Message as SessionMessage


logger = get_logger("agent")


class AgentState(Enum):
    """Agent state enumeration"""
    IDLE = "idle"
    WAITING = "waiting"
    PLANNING = "planning"
    EXECUTING = "executing"
    EVALUATING = "evaluating"
    REPLANING = "replaning"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


@dataclass
class AgentConfig:
    """Agent configuration"""
    name: str = "mai-agent"
    version: str = "1.0.0"
    max_steps: int = 20
    max_retries: int = 3
    execution_timeout: int = 300
    llm_provider: str = "minimax"
    llm_model: str = "MiniMax-M2.1"
    llm_api_key: Optional[str] = None


@dataclass
class AgentStats:
    """Agent execution statistics"""
    total_tasks: int = 0
    successful_tasks: int = 0
    failed_tasks: int = 0
    total_steps: int = 0
    total_duration_ms: int = 0
    current_state: AgentState = AgentState.IDLE


class ConversationalAgent:
    """Conversational Agent with session management and goal inference

    Features:
    - Session-based context isolation
    - Goal inference from conversation
    - Independent session contexts
    """

    def __init__(
        self,
        config: Optional[AgentConfig] = None,
        llm_api_key: Optional[str] = None
    ):
        self.config = config or AgentConfig()
        self.llm_api_key = llm_api_key or self.config.llm_api_key

        self.state = AgentState.IDLE

        self.session_manager = SessionManager()
        self.context_manager = ContextManager()

        self.episodic_memory: Optional[SessionEpisodicMemory] = None

        self.planner: Optional[Planner] = None
        self.executor: Optional[Executor] = None
        self.rule_critic: Optional[RuleCritic] = None
        self.llm_critic: Optional[LLMCritic] = None
        self.mcp_server_manager: Optional[MCPServerManager] = None
        self.audit_logger: Optional[AuditLogger] = None

        self.goal_inferencer = GoalInferencer()
        self.observation_parser = ObservationParser()
        self._stats = AgentStats()
        self._settings = get_settings()

    async def initialize(self) -> None:
        """Initialize agent components"""
        logger.info("Initializing conversational agent", name=self.config.name)

        self.state = AgentState.IDLE

        llm_provider = create_llm_provider(
            provider=self.config.llm_provider,
            api_key=self.llm_api_key,
            model=self.config.llm_model
        )

        self.mcp_server_manager = MCPServerManager()

        await self._setup_mcp_servers()

        available_tools = self.mcp_server_manager.list_tool_names() if self.mcp_server_manager else []

        self.planner = LLMPlanner(
            llm_provider=llm_provider,
            max_steps=self.config.max_steps,
            available_tools=available_tools
        )

        self.executor = Executor(
            max_retries=self.config.max_retries,
            execution_timeout=self.config.execution_timeout
        )

        self.rule_critic = RuleCritic(
            max_steps=self.config.max_steps,
            max_cost=100.0,
            allowed_tools=available_tools
        )

        self.llm_critic = LLMCritic(
            llm_provider=llm_provider,
            confidence_threshold=0.7
        )

        self.episodic_memory = SessionEpisodicMemory(db_path="./data/session_memory.db")
        await self.episodic_memory.initialize()

        self.audit_logger = AuditLogger(log_dir="./data/audit")

        logger.info("Conversational agent initialized successfully")

    async def _setup_mcp_servers(self) -> None:
        """Setup MCP server connections from mcp_config.json"""
        logger.info("Setting up MCP servers...")

        from ..mcp.manager import MCPServerManager

        self.mcp_server_manager = MCPServerManager()

        await self.mcp_server_manager.connect_servers()

        available_tools = self.mcp_server_manager.list_tool_names()

        if available_tools:
            logger.info(
                "MCP servers configured",
                total_tools=len(available_tools),
                sample_tools=available_tools[:5]
            )
        else:
            logger.info("No MCP tools available (no servers configured or none connected)")

    async def chat(
        self,
        session_id: Optional[str],
        user_message: str,
        user_metadata: Optional[Dict[str, Any]] = None
    ) -> Dict[str, Any]:
        """Process a chat message in a session"""
        session_id, session = await self.session_manager.get_or_create_session(session_id)

        session.add_message(
            role="user",
            content=user_message,
            **(user_metadata or {})
        )

        inferred_goal = self.goal_inferencer.infer_goal(session.messages, session.current_goal)

        if inferred_goal and inferred_goal != session.current_goal:
            logger.info(
                "Goal inferred from conversation",
                session_id=session_id,
                previous_goal=session.current_goal,
                new_goal=inferred_goal[:100]
            )
            session.current_goal = inferred_goal

        if not session.current_goal:
            return {
                "session_id": session_id,
                "status": "waiting",
                "message": "I understand. Please tell me what you'd like me to do.",
                "goal": None
            }

        session.add_message(role="assistant", content="I'll help you with that.")

        try:
            result = await self._execute_task(
                session_id=session_id,
                session=session
            )

            return {
                "session_id": session_id,
                "status": result["status"],
                "message": result.get("message", result.get("summary", "Task completed")),
                "goal": session.current_goal,
                "outputs": result.get("outputs", [])
            }

        except Exception as e:
            logger.error(
                "Chat processing failed",
                session_id=session_id,
                error=str(e)
            )

            return {
                "session_id": session_id,
                "status": "error",
                "message": str(e),
                "goal": session.current_goal
            }

    async def _execute_task(
        self,
        session_id: str,
        session: SessionContext
    ) -> Dict[str, Any]:
        """Execute task within session context"""
        goal = session.current_goal
        task_id = str(uuid4())

        logger.info(
            "Executing task in session",
            session_id=session_id,
            task_id=task_id,
            goal=goal[:100]
        )

        self._stats.total_tasks += 1
        self._stats.current_state = AgentState.PLANNING

        context = await self.context_manager.get_context(session_id)

        self.audit_logger.start_task(task_id, goal)

        try:
            context.set("task_id", task_id)
            context.set("goal", goal)

            plan = await self._create_plan_with_context(
                goal=goal,
                session=session,
                context=context
            )

            self.audit_logger.log_plan_created(
                task_id=task_id,
                plan_id=plan.plan_id,
                steps_count=len(plan.steps)
            )

            plan.mark_in_progress()

            self._stats.current_state = AgentState.EXECUTING

            result = await self._execute_plan(
                task_id=task_id,
                session_id=session_id,
                plan=plan,
                context=context
            )

            logger.debug(
                "Task execution completed",
                task_id=task_id,
                outputs_count=len(result.get("outputs", [])),
                state=result.get("state")
            )

            self._stats.successful_tasks += 1

            outputs = result.get("outputs", [])
            if outputs:
                response_parts = []
                for output in outputs:
                    if output.get("output") and output["output"].strip():
                        response_parts.append(output["output"].strip())
                    elif output.get("description"):
                        response_parts.append(output["description"])
                response_message = "\n\n".join(response_parts)
            else:
                response_message = plan.summary or "Task completed successfully."

            return {
                "task_id": task_id,
                "status": "success",
                "goal": goal,
                "message": response_message,
                "summary": plan.summary,
                "steps_completed": len([s for s in plan.steps if s.status.value == "completed"]),
                "plan_id": plan.plan_id,
                "outputs": outputs
            }

        except asyncio.CancelledError:
            self.state = AgentState.CANCELLED
            await self._save_session_result(
                session_id, task_id, goal, "cancelled", success=False
            )
            raise

        except Exception as e:
            self._stats.failed_tasks += 1
            self.state = AgentState.FAILED

            await self._save_session_result(
                session_id, task_id, goal, str(e), success=False
            )

            return {
                "task_id": task_id,
                "status": "failed",
                "goal": goal,
                "message": f"Task failed: {str(e)}",
                "error": str(e),
                "summary": None,
                "steps_completed": len([s for s in plan.steps if s.status.value == "completed"]) if 'plan' in dir() else 0,
                "plan_id": plan.plan_id if 'plan' in dir() else None
            }

        finally:
            self._stats.current_state = self.state

    async def _create_plan_with_context(
        self,
        goal: str,
        session: SessionContext,
        context: InMemoryContext
    ) -> Plan:
        """Create plan with session context"""
        self.state = AgentState.PLANNING

        session_history = await self.episodic_memory.get_session_history(
            session.session_id, limit=5
        )

        context_parts = []

        if session_history:
            context_parts.append("Previous tasks in this session:")
            for item in session_history[:3]:
                context_parts.append(
                    f"- Goal: {item['goal'][:50]}... | Outcome: {item['outcome']}"
                )

        recent_observations = context.get_observations()
        if recent_observations:
            context_parts.append("\nRecent observations:")
            for obs in recent_observations[-3:]:
                context_parts.append(f"- {obs[:100]}")

        context_str = "\n".join(context_parts) if context_parts else ""

        plan = await self.planner.create_plan(
            goal=goal,
            context=context_str,
            task_id=context.get("task_id") or str(uuid4())
        )

        return plan

    async def _execute_plan(
        self,
        task_id: str,
        session_id: str,
        plan: Plan,
        context: InMemoryContext
    ) -> Dict[str, Any]:
        """Execute plan steps and return execution summary"""
        all_outputs = []

        for step_index in range(len(plan.steps)):
            if self.state in [AgentState.COMPLETED, AgentState.FAILED]:
                break

            step = plan.steps[step_index]
            plan.current_step = step_index

            logger.info(
                "Executing step",
                task_id=task_id,
                step=step.step_number,
                description=step.description
            )

            self.audit_logger.log_step_started(
                task_id=task_id,
                plan_id=plan.plan_id,
                step_id=step.step_id,
                step_description=step.description
            )

            try:
                result = await self._execute_step(task_id, step, session_id)

                all_outputs.append({
                    "step": step.step_number,
                    "description": step.description,
                    "tool": step.tool,
                    "output": result.output
                })

                logger.debug(
                    "Step output collected",
                    task_id=task_id,
                    step=step.step_number,
                    tool=step.tool,
                    output_preview=str(result.output)[:50],
                    total_outputs=len(all_outputs)
                )

                step.mark_completed(result.output or "Completed")

                self.audit_logger.log_step_completed(
                    task_id=task_id,
                    plan_id=plan.plan_id,
                    step_id=step.step_id,
                    result=result.output or "",
                    duration_ms=result.duration_ms
                )

                if result.success:
                    context.add_observation(result.output or "")

                evaluation = await self._evaluate_step(
                    task_id, session_id, step, result, context, len(plan.steps)
                )

                if evaluation.decision == Decision.STOP:
                    logger.info(
                        "Critic decided to stop",
                        task_id=task_id,
                        step=step.step_number,
                        reason=evaluation.reason
                    )
                    plan.mark_completed(evaluation.reason)
                    self.state = AgentState.COMPLETED
                    break

                elif evaluation.decision == Decision.REPLAN:
                    logger.info(
                        "Critic decided to replan",
                        task_id=task_id,
                        step=step.step_number,
                        reason=evaluation.reason
                    )
                    plan = await self._replan(task_id, plan, evaluation.reason, context)
                    break

            except Exception as e:
                logger.error(
                    "Step execution failed",
                    task_id=task_id,
                    step=step.step_id,
                    error=str(e)
                )

                self._stats.total_steps += 1
                self._stats.failed_steps += 1

                self.audit_logger.log_error(
                    task_id=task_id,
                    step_id=step.step_id,
                    error=str(e)
                )

                if step.retry_count < self.config.max_retries:
                    step.retry_count += 1
                    continue

                plan.mark_failed(str(e))
                self.state = AgentState.FAILED

        if self.state not in [AgentState.COMPLETED, AgentState.FAILED]:
            if all(s.status.value == "completed" for s in plan.steps):
                plan.mark_completed("All steps completed")
                self.state = AgentState.COMPLETED

        await self._save_session_result(
            session_id, task_id, plan.goal,
            plan.outcome or ("Success" if self.state == AgentState.COMPLETED else "Failed"),
            summary=plan.summary,
            success=self.state == AgentState.COMPLETED
        )

        return {
            "outputs": all_outputs,
            "state": self.state.value,
            "steps_completed": len([s for s in plan.steps if s.status.value == "completed"]),
            "total_steps": len(plan.steps)
        }

    async def _execute_step(
        self,
        task_id: str,
        step,
        session_id: str
    ) -> ExecutionResult:
        """Execute a single step"""
        if not step.tool:
            return ExecutionResult(
                success=True,
                output=f"Thought: {step.description}",
                duration_ms=0
            )

        parameters = step.parameters or {}

        self.audit_logger.log_tool_called(
            task_id=task_id,
            plan_id="",
            step_id=step.step_id,
            tool_name=step.tool,
            parameters=parameters
        )

        try:
            output = await self._call_tool(step.tool, parameters)

            self.audit_logger.log_tool_result(
                task_id=task_id,
                plan_id="",
                step_id=step.step_id,
                tool_name=step.tool,
                success=True,
                output=output
            )

            return ExecutionResult(
                success=True,
                output=output,
                duration_ms=0
            )

        except Exception as e:
            self.audit_logger.log_tool_result(
                task_id=task_id,
                plan_id="",
                step_id=step.step_id,
                tool_name=step.tool,
                success=False,
                output=str(e)
            )

            raise

    async def _call_tool(self, tool_name: str, parameters: Dict[str, Any]) -> str:
        """Call a tool through MCP server manager"""
        if not self.mcp_server_manager:
            raise RuntimeError("MCP server manager not initialized")

        if "." not in tool_name:
            raise ValueError(f"Invalid tool name format: {tool_name}")

        server_name, tool_short_name = tool_name.split(".", 1)

        if server_name not in self.mcp_server_manager._connections:
            raise ValueError(f"Unknown MCP server: {server_name}")

        connection = self.mcp_server_manager._connections[server_name]

        try:
            result = await connection.call_tool(tool_short_name, parameters)

            if isinstance(result, dict):
                content = result.get("content", [])
                if isinstance(content, list):
                    text_parts = []
                    for block in content:
                        if block.get("type") == "text":
                            text_parts.append(block.get("text", ""))
                    return "\n".join(text_parts)
                return str(content)
            return str(result)

        except Exception as e:
            logger.error(
                "Tool call failed",
                tool=tool_name,
                error=str(e)
            )
            raise

    async def _evaluate_step(
        self,
        task_id: str,
        session_id: str,
        step,
        result: ExecutionResult,
        context: InMemoryContext,
        total_steps: int = 0
    ) -> CriticResult:
        """Evaluate step execution"""
        self.state = AgentState.EVALUATING

        observations = context.get_observations()

        rule_input = RuleCriticInput(
            current_step=step.step_number,
            total_steps=total_steps,
            tool_name=step.tool,
            tool_parameters=step.parameters or {},
            execution_result=result.output,
            execution_success=result.success,
            cost_estimate=0.0,
            allowed_tools=self.rule_critic.allowed_tools,
            max_steps=self.config.max_steps,
            max_cost=100.0
        )

        rule_result = self.rule_critic.evaluate(rule_input)

        try:
            llm_input = LLMCriticInput(
                goal=context.get("goal") or "",
                current_step=step.step_number,
                total_steps=total_steps,
                observations=observations,
                completed_steps=[],
                execution_result=result.output,
                execution_success=result.success
            )

            llm_result = await self.llm_critic.evaluate(llm_input)

            self.audit_logger.log_critic_decision(
                task_id=task_id,
                plan_id="",
                decision=llm_result.decision.value,
                confidence=llm_result.confidence,
                reason=llm_result.reason
            )

            if rule_result.decision == Decision.STOP:
                return rule_result

            if llm_result.decision == Decision.STOP and llm_result.confidence >= 0.7:
                return llm_result

            return llm_result

        except Exception as e:
            logger.debug(
                "LLM critic evaluation failed, using rule critic result",
                error=str(e)
            )
            return rule_result

    async def _replan(
        self,
        task_id: str,
        original_plan: Plan,
        reason: str,
        context: InMemoryContext
    ) -> Plan:
        """Replan based on feedback"""
        self.state = AgentState.REPLANING

        self.audit_logger.log_event(
            AuditEventType.PLAN_UPDATED,
            task_id=task_id,
            plan_id=original_plan.plan_id,
            message=f"Replanning: {reason}",
            details={"reason": reason}
        )

        new_plan = await self.planner.replan(
            original_plan=original_plan,
            feedback=reason,
            current_step=original_plan.current_step
        )

        self.audit_logger.log_plan_created(
            task_id=task_id,
            plan_id=new_plan.plan_id,
            steps_count=len(new_plan.steps)
        )

        self.state = AgentState.EXECUTING

        return new_plan

    async def _save_session_result(
        self,
        session_id: str,
        task_id: str,
        goal: str,
        outcome: str,
        summary: Optional[str] = None,
        success: bool = True
    ) -> None:
        """Save session result to episodic memory"""
        if not self.episodic_memory:
            return

        await self.episodic_memory.save_session(
            session_id=session_id,
            task_id=task_id,
            goal=goal,
            outcome=outcome,
            summary=summary,
            success=success
        )

    async def end_session(self, session_id: str) -> bool:
        """End a session"""
        await self.context_manager.delete_context(session_id)
        return await self.session_manager.end_session(session_id)

    async def delete_session(self, session_id: str) -> bool:
        """Delete a session"""
        await self.context_manager.delete_context(session_id)
        return await self.session_manager.delete_session(session_id)

    def get_state(self) -> Dict[str, Any]:
        """Get current agent state"""
        return {
            "state": self.state.value,
            "stats": self._stats.__dict__
        }

    def get_statistics(self) -> Dict[str, Any]:
        """Get agent execution statistics"""
        session_stats = self.session_manager.get_statistics()
        context_stats = self.context_manager.get_statistics()

        return {
            **self._stats.__dict__,
            "success_rate": (
                self._stats.successful_tasks / self._stats.total_tasks * 100
                if self._stats.total_tasks > 0 else 0
            ),
            "sessions": session_stats,
            "contexts": context_stats
        }

    async def close(self) -> None:
        """Close agent and cleanup resources"""
        logger.info("Closing agent")

        if self.planner:
            await self.planner.close()

        if self.executor:
            await self.executor.close()

        if self.mcp_server_manager:
            await self.mcp_server_manager.close()

        if self.episodic_memory:
            await self.episodic_memory.close()

        if self.audit_logger:
            await self.audit_logger.close()

        await self.context_manager.clear_all()
        await self.session_manager.cleanup()

        self.state = AgentState.IDLE
        logger.info("Agent closed")


async def create_agent(
    name: str = "mai-agent",
    llm_api_key: Optional[str] = None
) -> ConversationalAgent:
    """Create and initialize agent"""
    config = AgentConfig(name=name, llm_api_key=llm_api_key)
    agent = ConversationalAgent(config=config, llm_api_key=llm_api_key)
    await agent.initialize()
    return agent
