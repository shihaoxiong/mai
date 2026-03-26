# mai

## Docs

- [DeerFlow backend 与 Spring AI Alibaba 能力分析](docs/java-deerflow/01-analysis.md)
- [Java DeerFlow Backend 需求文档](docs/java-deerflow/02-requirements.md)
- [Java DeerFlow Backend 架构设计](docs/java-deerflow/03-architecture.md)
- [Java DeerFlow Backend 开发计划（Codex 任务列表版）](docs/java-deerflow/04-development-plan.md)

## Run

- `chmod +x mvnw`
- `./mvnw spring-boot:run`
- health check: `http://localhost:8080/actuator/health`
- SSE demo: `http://localhost:8080/api/p0/stream?threadId=demo-thread`
- tests: `./mvnw test`
