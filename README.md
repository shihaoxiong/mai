# mai

## Docs

- [DeerFlow backend 与 Spring AI Alibaba 能力分析](docs/java-deerflow/01-analysis.md)
- [Java DeerFlow Backend 需求文档](docs/java-deerflow/02-requirements.md)
- [Java DeerFlow Backend 架构设计](docs/java-deerflow/03-architecture.md)
- [Java DeerFlow Backend 开发计划（Codex 任务列表版）](docs/java-deerflow/04-development-plan.md)
- [Java DeerFlow Backend P0 运行时契约](docs/java-deerflow/05-runtime-contract.md)

## Run

- `chmod +x mvnw`
- `./mvnw spring-boot:run`
- health check: `http://localhost:8080/actuator/health`
- SSE demo: `http://localhost:8080/api/p0/stream?threadId=demo-thread`
- create thread: `POST /api/threads`
- get thread: `GET /api/threads/{threadId}`
- run thread: `POST /api/threads/{threadId}/runs`，默认返回 `ThreadStateSnapshot`；当请求 `Accept: text/event-stream` 时会直接返回该次 run 的 SSE 事件流。请求体支持可选 `userId`，以及最小可用的 run 级参数：`model_name`、`is_plan_mode`、`subagent_enabled`、`max_concurrent_subagents`
- delete thread: `DELETE /api/threads/{threadId}`
- list models: `GET /api/models`
- upload files: `POST /api/threads/{threadId}/uploads`
- list uploads: `GET /api/threads/{threadId}/uploads/list`
- delete upload: `DELETE /api/threads/{threadId}/uploads/{filename}`
- list artifacts: `GET /api/threads/{threadId}/artifacts/list`
- read artifact: `GET /api/threads/{threadId}/artifacts/{artifactPath}`
- stream events: `GET /api/threads/{threadId}/events`
- submit approval: `POST /api/threads/{threadId}/approvals/{approvalId}`
- resume thread: `POST /api/threads/{threadId}/resume`
- tests: `./mvnw test`

## Runtime Persistence

- lead agent 会话 checkpoint 默认落在 `data/checkpoints/lead-agent/`
- `GET /api/threads/{threadId}` 当前直接基于 lead agent state 投影线程展示态
- 审批恢复所需的 pending approval 与线程 `userId` 绑定当前也保存在 lead agent checkpoint 中
- runtime MCP tools 当前支持 `stdio`、HTTP SSE 与 streamable HTTP 三类 transport，并继续复用 deferred tools / `tool_search` 主链路
- 长期记忆当前已升级为 `facts + structured profile` 共存的文件存储，并通过 debounce 队列异步更新
