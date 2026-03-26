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
- run thread: `POST /api/threads/{threadId}/runs`
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
