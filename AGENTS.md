# AGENTS.md

## 1. Project Context

This repository is for a Java implementation of the `deer-flow` backend, built on top of Spring AI Alibaba.

The target is not a 1:1 rewrite of the original Python implementation. The goal is to build a maintainable Java backend that preserves the core DeerFlow product experience for long-running agent tasks.

Primary goals:

- Support long-running, multi-step agent tasks with pause and resume.
- Support `threadId` based state persistence and recovery.
- Provide isolated per-thread file workspaces.
- Support built-in tools, MCP tools, skills, memory, artifacts, and subtasks.
- Expose both runtime APIs and platform/admin APIs.

## 2. What This Project Is

Treat this project as an agent platform backend, not just a chat service.

Core capability areas:

- Runtime orchestration for long-running tasks
- Thread persistence and checkpoint recovery
- Per-thread `workspace/uploads/outputs`
- Sandbox-backed file and shell tools
- File upload and document-to-Markdown conversion
- Skills and MCP management
- Short-term and long-term memory
- Artifact management
- Subtask / multi-agent execution

## 3. What This Project Is Not

- Do not aim for strict internal parity with DeerFlow Python modules.
- Do not optimize for a team-scale microservice split in the first implementation.
- Do not start with IM integrations, billing, or full multi-tenant architecture.
- Do not skip platform-layer engineering and focus only on the Agent loop.

## 4. Source Documents

Read these documents before making substantial design or implementation changes:

- [README.md](./README.md)
- [docs/java-deerflow/01-analysis.md](./docs/java-deerflow/01-analysis.md)
- [docs/java-deerflow/02-requirements.md](./docs/java-deerflow/02-requirements.md)
- [docs/java-deerflow/03-architecture.md](./docs/java-deerflow/03-architecture.md)
- [docs/java-deerflow/04-development-plan.md](./docs/java-deerflow/04-development-plan.md)
- [docs/java-deerflow/05-runtime-contract.md](./docs/java-deerflow/05-runtime-contract.md)

External references used to shape the design:

- DeerFlow backend README: <https://github.com/bytedance/deer-flow/blob/main/backend/README.md>
- Spring AI Alibaba overview: <https://java2ai.com/docs/overview>

## 5. Current Repo Status

At the moment, this repository mainly contains planning and design documents.

Important implication:

- If asked to start coding, begin from the tasks in `docs/java-deerflow/04-development-plan.md`.
- Do not assume an existing Spring Boot project structure is already present.
- Prefer creating the implementation incrementally from the documented `P0 -> P1 -> P2 -> P3 -> P4` order.

## 6. Product and Architecture Constraints

These are the default architectural decisions unless the user explicitly asks to change them.

### 6.1 High-level architecture

- Use Spring Boot as the unified backend application.
- Start with a modular monolith, not multiple deployable services.
- Expose runtime APIs and platform/admin APIs from the same application in the first version.

### 6.2 Agent runtime model

- Use Spring AI Alibaba Graph for outer workflow orchestration.
- Use `ReactAgent` for the main reasoning loop.
- Keep workflow concerns in Graph nodes/services.
- Keep reasoning-loop concerns in hooks/interceptors/tools.

### 6.3 Persistence and task boundary

- `threadId` is the primary long-running session boundary.
- Thread state must be recoverable after interruption.
- Thread deletion must clean both persisted state and thread-local files.

### 6.4 File isolation model

- Each thread must have isolated `workspace`, `uploads`, and `outputs`.
- Tools must operate on virtual thread-scoped paths, not unrestricted host paths.
- Path traversal and cross-thread access must be blocked.

### 6.5 Sandbox model

- Development can start with a local process sandbox.
- Production direction is container-based sandboxing.
- Sandbox capability is a first-class platform concern, not an afterthought.

### 6.6 Platform capabilities

These capabilities are required for the backend to be considered DeerFlow-like:

- Models registry
- MCP config and hot reload
- Skills registry and toggle
- Upload handling and Markdown conversion
- Artifact listing and download
- Memory injection and extraction
- Approval/pause/resume support
- Subtask execution

## 7. Preferred Tech Direction

Unless the user asks otherwise, prefer:

- Java 17+ for compatibility, with Java 21 as the long-term preferred target
- Spring Boot 3.x
- Spring AI Alibaba
- Spring WebFlux for SSE and async flows
- PostgreSQL for durable checkpoint/config/memory direction
- Redis as optional acceleration layer

Current repo baseline:

- The current `pom.xml` uses Java 17 because that matches the available local runtime.
- If later tasks require Java 21-specific capabilities, update both code and docs together.

## 8. Implementation Priorities

If no other instruction is given, start from these tasks:

1. `P2-09` Implement the approval recovery path.
2. `P2-10` Add platform API integration coverage.
3. `P3-01` Implement `MemoryStore`.
4. `P3-02` Implement `MemoryExtractorJob`.
5. `P3-03` Implement memory injection strategy.

Do not jump into advanced memory, subtasks, or production hardening before the minimal runtime path works end to end.

## 9. Working Rules for Codex

When implementing in this repo:

- Read the relevant docs before changing architecture or APIs.
- Keep code changes aligned with the documented requirements and architecture.
- If implementation decisions cause doc drift, update the docs in the same task.
- Prefer incremental, testable milestones.
- When starting a new phase, update `docs/java-deerflow/04-development-plan.md` task states if appropriate.

If the user asks for a review:

- Focus on bugs, regressions, missing tests, and mismatches with the documented architecture.

If a requirement is unclear:

- Prefer the documented requirements and architecture over ad hoc assumptions.
- If the docs are insufficient, make the smallest safe assumption and document it.

## 10. Definition of Done

A task should generally not be treated as complete unless:

- The code is implemented.
- There is at least one manual or automated verification path.
- Any affected docs are updated.
- No hidden blocker is left behind for the next task; if there is one, record it explicitly.

## 11. Near-term Deliverable Shape

The expected first concrete deliverable is a minimal but runnable backend skeleton that includes:

- Spring Boot application bootstrap
- Health endpoint
- Initial runtime API
- Basic Graph + ReactAgent integration
- SSE streaming path
- Thread-scoped workspace creation

## 12. If You Need More Context

Start with these files, in order:

1. [docs/java-deerflow/04-development-plan.md](./docs/java-deerflow/04-development-plan.md)
2. [docs/java-deerflow/02-requirements.md](./docs/java-deerflow/02-requirements.md)
3. [docs/java-deerflow/05-runtime-contract.md](./docs/java-deerflow/05-runtime-contract.md)
4. [docs/java-deerflow/03-architecture.md](./docs/java-deerflow/03-architecture.md)
5. [docs/java-deerflow/01-analysis.md](./docs/java-deerflow/01-analysis.md)
