# Java DeerFlow Backend 架构设计

## 1. 设计目标

本架构用于支撑“长期工作任务型 Agent Backend”，重点解决以下问题：

- 如何让 Agent 持续运行、可中断、可恢复。
- 如何让每个线程拥有独立的工作文件环境。
- 如何在 Java 生态里承接 DeerFlow 的 Skills、MCP、Memory、Artifacts、Subtasks 能力。
- 如何把 Spring AI Alibaba 的运行时能力与平台 API 组合成一套完整 backend。

## 2. 总体设计原则

- 运行时与平台能力分层，但首期采用单体部署，降低复杂度。
- 当前实现已收敛为直接使用 `ReactAgent` 承担主执行链路，线程状态统一从 lead agent state 投影。
- 所有长任务都以 `threadId` 为恢复边界。
- 文件、工具、记忆、审批、子任务都要纳入统一状态机。
- 对前端暴露稳定 API，对内部保留可替换实现。

## 3. 推荐部署形态

首期建议采用“模块化单体”：

- 一个 Spring Boot 应用。
- 对外同时提供 Runtime API 和 Gateway/Admin API。
- 内部拆为若干逻辑模块。

这样比 DeerFlow 的双进程结构更适合 Java 项目启动阶段，因为：

- 避免 Runtime 与 Gateway 的跨服务状态同步。
- 删除线程时，checkpoint、workspace、artifacts 可在一个事务边界内协调。
- 鉴权、审计、配置、日志和指标只需维护一套。

后续如果压力增大，再拆为：

- `runtime-service`
- `platform-service`
- `sandbox-service`
- `memory-worker`

## 4. 逻辑架构

```mermaid
flowchart LR
    Client["Web / API Client"] --> Api["Spring Boot API Layer"]
    Api --> Runtime["Runtime Orchestrator"]
    Api --> Admin["Platform Admin Services"]

    Runtime --> Agent["ReactAgent / Multi-Agent"]
    Agent --> Tools["Tool Registry"]

    Tools --> Sandbox["Sandbox SPI"]
    Tools --> Mcp["MCP Client Manager"]
    Tools --> Skills["Skill Context Provider"]
    Tools --> Subtasks["Subtask Executor"]

    Runtime --> Checkpoint["Checkpoint Store"]
    Runtime --> Memory["Memory Store / Extractor"]
    Runtime --> Files["Thread Workspace & Artifact Store"]

    Admin --> Config["Runtime Config Repository"]
    Admin --> Files
    Admin --> Mcp
    Admin --> Skills
    Admin --> Memory
```

## 5. 模块划分

以下是逻辑模块，不强制要求一开始就拆成 Maven 多模块。

### 5.1 API Layer

职责：

- 提供线程、运行、上传、产物、模型、MCP、技能、审批等 REST/SSE 接口。
- 对外屏蔽内部 Graph/Agent 细节。

建议子模块：

- `runtime-api`
- `platform-api`

### 5.2 Runtime Orchestrator

职责：

- 创建/恢复 `threadId` 对应的运行上下文。
- 组装 Graph、ReactAgent、Hook、Interceptor、Tool Registry。
- 统一处理运行状态、审批中断、子任务、后处理。

### 5.3 Agent Runtime

职责：

- 承担 lead agent 的 ReAct 循环。
- 调用 built-in tools、MCP tools、subagent tool。
- 通过 Hook/Interceptor 注入规划、压缩、审批、上下文裁剪。

### 5.4 Thread Workspace Service

职责：

- 管理线程目录创建、清理、虚拟路径映射。
- 暴露 `workspace/uploads/outputs` 位置。
- 为 Sandbox 与 ArtifactService 提供统一路径解析。

### 5.5 Sandbox SPI

职责：

- 屏蔽本地进程执行与容器执行差异。
- 提供命令执行、目录查询、文件读写、替换等能力。
- 统一审计命令、超时、资源限制和路径校验。

建议实现：

- `LocalProcessSandboxProvider`：开发环境。
- `ContainerSandboxProvider`：生产环境。

### 5.6 Skill Module

职责：

- 发现技能、读取技能元数据、控制启用状态。
- 将技能内容和允许工具信息注入 prompt/context。
- 支持未来的安装、版本和依赖约束。

### 5.7 MCP Module

职责：

- 管理 MCP 配置与生命周期。
- 为 Tool Registry 暴露统一工具视图。
- 响应配置变更并热加载。

### 5.8 Memory Module

职责：

- 短期记忆：依赖 Graph Checkpoint。
- 长期记忆：依赖 Store 持久化用户画像、事实、偏好。
- 抽取器：运行后异步提取事实，写入 Store。

### 5.9 Artifact / File Module

职责：

- 处理文件上传、转换、列表、删除、下载。
- 管理 Agent 生成产物及元数据。
- 为前端提供 HTTP 访问入口。

当前骨架实现：

- 已支持线程级上传、列表和删除。
- 已支持为 `.txt/.md` 生成或复用 Markdown 视图。
- 对 `pdf/ppt/pptx/xls/xlsx/doc/docx` 当前会生成可诊断的 Markdown 占位文件，后续可替换为真实解析器。
- 已支持线程级产物列表与文件访问接口。

### 5.10 Config Module

职责：

- 管理模型、MCP、技能开关、功能参数等动态配置。
- 提供热加载能力。

建议设计：

- 静态配置放 `application.yml`。
- 动态配置抽象成 `RuntimeConfigRepository`。
- MVP 用文件实现，后续可换 JDBC/配置中心实现。

## 6. 核心运行模型

当前实现已收敛为“Lead Agent 直接执行 + Lead Agent State 持久化”。

### 6.1 主链路

```mermaid
flowchart TD
    A["Run Request"] --> B["Prepare Thread Context"]
    B --> C["Inject Memory / Build Agent Input"]
    C --> D["Call Lead Agent"]
    D --> E["Project Lead Agent State"]
    E --> F["Persist Artifacts / Generate Title / Suggestions"]
    F --> G["Return ThreadStateSnapshot"]
```

说明：

- `Prepare Thread Context`：确保线程目录、checkpoint、审批状态与线程上下文就绪。
- `Inject Memory / Build Agent Input`：执行长期记忆注入，并构造真正传给 lead agent 的输入。
- `Call Lead Agent`：直接调用 `ReactAgent`。
- `Project Lead Agent State`：从 lead agent state 中提取 `messages/todos/approval/threadContext` 等平台字段。
- `Persist Artifacts / Generate Title / Suggestions`：补齐线程展示态。

### 6.2 Hook / Interceptor 映射

| DeerFlow 思路 | Java 方案 |
| --- | --- |
| SummarizationMiddleware | `SummarizationHook` |
| TodoListMiddleware | `TodoListInterceptor` |
| ClarificationMiddleware | `HumanInTheLoopHook` + 自定义 ApprovalState |
| ViewImageMiddleware | 自定义 `ContextAssembler`，按模型能力注入 |
| MemoryMiddleware | `Store` + `MemoryExtractorJob` |
| Skills 注入 | `SkillContextProvider` |
| Uploads 注入 | `UploadContextProvider` |

结论：

- Agent loop 内的行为，用 Hook/Interceptor。
- 线程查询与恢复统一从 lead agent state 投影，不再依赖 outer graph。

当前骨架实现：

- Runtime lead agent 已接入 `SummarizationHook`
- Runtime lead agent 已接入 `TodoListInterceptor`
- Runtime lead agent 已接入 `ask_clarification` 工具与对应拦截器，可直接把 run 转入 `WAITING_CLARIFICATION`
- Runtime lead agent 已接入 tool error handling 与 dangling tool-call patch；普通工具异常会被格式化为工具结果继续回流，不完整 tool-call 历史会在下一轮模型调用前补齐占位响应
- Runtime lead agent 已接入 tool-call 安全拦截器，当前会限制单轮 `task` fan-out，并在重复 tool-call 循环达到阈值时强制停下
- Runtime lead agent 已接入 `RuntimeThreadContextInterceptor`，会在每轮模型调用前临时注入 `thread_data / uploaded_files` 上下文，但不污染持久化消息历史
- Runtime lead agent 已接入 todo reminder 拦截器；当 todo 状态仍存在但 `write_todos` 已离开当前上下文窗口时，会补一条提醒消息
- Runtime lead agent 已接入 `view_image` 工具与图像上下文注入拦截器，可在下一轮模型调用前重新注入图片内容
- Runtime lead agent 已接入最小可用的 deferred tools / `tool_search`；当前已支持线程内文件工具与真实 stdio / HTTP SSE / streamable HTTP MCP tools 的延迟发现与执行
- 已提供 `RuntimeMcpToolProvider`，会基于平台层 `McpServerConfig` 建立 stdio / HTTP MCP 连接、缓存工具并在配置变更时失效重载
- 已提供 `RuntimeLeadAgentPromptService`，按线程聚合 skills、uploads、workspace、agent soul、结构化长期记忆与研究型输出规则，并在 lead agent 创建时注入 system prompt
- `write_todos` 工具结果当前会同步投影到 lead agent checkpoint 的 `todos` 状态，供线程查询与恢复直接复用
- Runtime lead agent 已接入 `task` 工具，可把委派请求转交给 `SubTaskExecutor`
- `SubTaskExecutor` 已接入 `SequentialAgent` 和 `ParallelAgent`，当前可在子任务层跑通一个串行和一个并行编排场景
- 已提供 `PostRunGenerationService`，会在 run 完成后生成标题和建议问题，替换原先的静态占位值
- 审批恢复链路当前支持 `APPROVE`、`REJECT`、`REQUEST_CLARIFICATION` 三种结果；`REQUEST_CLARIFICATION` 会让线程进入 `WAITING_CLARIFICATION`
- `POST /api/threads/{threadId}/runs` 当前已支持按 `Accept` 头协商返回同步 JSON 或 run 级 SSE 事件流；run 级 SSE 继续复用统一的 `RunEventType`
- run 级 SSE 当前已直接消费 `leadAgent.streamMessages(...)` 的真实流式输出，不再在 `run.completed` 后人为补发伪造 `token.delta`
- run 级 SSE 当前会把 assistant 文本增量映射为 `token.delta`，把工具发起和工具结果映射为 `tool.call.started / tool.call.completed`
- 当前 run 级流式依赖底层 `ChatModel.stream(...)`；如果 provider 未实现流式接口，run 会快速失败而不是静默降级到伪流式
- 当前多 `Generation` 归一化仍未接入 runtime chat model；若 provider 在单轮或单个流式 chunk 中返回多个候选 generation，底层 agent 仍可能只消费其中一条
- `POST /api/threads/{threadId}/runs` 当前已支持最小可用的 run 级参数覆盖：`model_name / is_plan_mode / subagent_enabled / max_concurrent_subagents`；参数会随待审批上下文一起持久化并在 resume 时复用

## 7. 状态模型设计

建议在 Spring AI Alibaba 的 Graph `State` 基础上扩展如下业务状态。

| Key | 类型 | 策略 | 说明 |
| --- | --- | --- | --- |
| `messages` | `List<Message>` | Append | 会话消息与工具观察结果 |
| `threadContext` | `Map` | Replace | 线程元数据、用户、租户、运行参数 |
| `workspace` | `Map` | Replace | `workspace/uploads/outputs` 真实路径与虚拟路径 |
| `artifacts` | `List<ArtifactRef>` | Append | 已生成产物 |
| `uploads` | `List<UploadRef>` | Replace | 已上传文件及 Markdown 派生文件 |
| `todos` | `List<TodoItem>` | Replace | 计划模式下的任务列表 |
| `approval` | `ApprovalState` | Replace | 待审批/已审批/拒绝/澄清 |
| `memoryContext` | `List<MemoryFact>` | Replace | 注入给 Agent 的长期记忆 |
| `subTasks` | `List<SubTaskRef>` | Replace | 子任务状态 |
| `runStatus` | `String` | Replace | `RUNNING/WAITING_APPROVAL/FAILED/COMPLETED` |
| `title` | `String` | Replace | 会话标题 |
| `suggestions` | `List<String>` | Replace | 后续建议问题 |

设计原则：

- 高频变化但需要完整保留的对象使用 Append。
- 面向展示的聚合结果使用 Replace，避免 checkpoint 膨胀。

## 8. API 设计建议

为了便于前端逐步迁移，建议接口尽量兼容 DeerFlow 的资源模型。

### 8.1 Runtime API

- `POST /api/threads`
- `GET /api/threads/{threadId}`
- `DELETE /api/threads/{threadId}`
- `POST /api/threads/{threadId}/runs`
  说明：默认返回同步快照；当 `Accept: text/event-stream` 时直接返回该次 run 的 SSE 事件流。当前流式返回已直接连接 lead agent 的流式输出链路
- `GET /api/threads/{threadId}/state`
- `POST /api/threads/{threadId}/resume`
- `POST /api/threads/{threadId}/approvals/{approvalId}`
- `GET /api/threads/{threadId}/events`

如果明确要兼容 DeerFlow 现有前端，也可以增加兼容路由：

- `POST /api/langgraph/threads/{threadId}/runs`
- `DELETE /api/langgraph/threads/{threadId}`

内部仍然转发到统一的 Java Runtime Controller。

### 8.2 Platform API

- `GET /api/models`
- `GET /api/mcp/config`
- `PUT /api/mcp/config`
- `GET /api/skills`
- `GET /api/skills/{skillName}`
- `POST /api/skills/{skillName}/enable`
- `POST /api/skills/{skillName}/disable`
- `POST /api/skills/install`
- `POST /api/threads/{threadId}/uploads`
- `GET /api/threads/{threadId}/uploads/list`
- `DELETE /api/threads/{threadId}/uploads/{filename}`
- `GET /api/threads/{threadId}/artifacts/**`
- `GET /api/memory/status`

## 9. 数据存储设计

### 9.1 Checkpoint

推荐：

- 开发环境：`MemorySaver` 或 `RedisSaver`
- 生产环境：`PostgreSqlSaver`

原因：

- Thread/checkpoint 是核心恢复能力，必须持久化。
- PostgreSQL 更适合作为主存储，便于审计、恢复和运维。

### 9.2 长期记忆

推荐抽象：

- `MemoryStore` 接口
- 默认实现：首版先提供 `FileMemoryStore`，使用本地 JSON 文件按用户维度持久化
- 生产增强：可替换为 PostgreSQL 或 Redis 的 KV/JSON 存储
- 后续增强：向量库检索、分层记忆、用户画像索引

当前骨架实现：

- 默认使用 `data/memory/` 作为长期记忆根目录。
- 每个用户的长期记忆单独存成一个 JSON 文件，避免与 thread 工作区生命周期耦合。
- `FileMemoryStore` 当前同时保存 facts 列表和 `StructuredMemoryProfile`，让结构化画像与可筛选事实共存于同一份用户记忆文件中。
- 读取时支持按 `limit` 和 `minConfidence` 做 facts 筛选，注入时则会优先读取结构化 profile 再补充高置信 facts。
- 已实现 `MemoryUpdateQueue` 与升级后的 `MemoryExtractorJob`；run 成功结束后会先进入 debounce 队列，再异步更新 facts 与结构化 profile。
- 当前通过 `POST /api/threads/{threadId}/runs` 请求体中的可选 `userId` 绑定线程与用户；缺少 `userId` 时会跳过长期记忆抽取。
- 已实现 `MemoryInjectionService`，当前会把结构化 profile 与高置信 facts 一并按 `append-to-user-input` 策略注入 lead agent 输入，不再依赖旧的 outer graph context node。

### 9.3 文件与产物

推荐：

- MVP：本地文件系统
- 生产增强：S3/OSS 兼容对象存储

目录建议：

```text
${app.data-dir}/threads/{threadId}/
  workspace/
  uploads/
  outputs/
  metadata/
```

当前骨架实现：

- 默认使用 `data/threads/{threadId}/` 作为线程工作区根目录。
- 通过 `mai.workspace.base-dir` 配置工作区根目录。
- lead agent 会话 checkpoint 当前使用 `FileSystemSaver` 持久化到 `data/checkpoints/lead-agent/`。
- `GET /api/threads/{threadId}` 当前直接从 lead agent state 投影线程展示态。
- 审批恢复所需的 pending approval 与线程 `userId` 绑定当前也保存在 lead agent checkpoint 中；线程 metadata 目录主要保留给子任务等线程本地文件态。

### 9.4 动态配置

推荐抽象：

- `RuntimeConfigRepository`

实现顺序：

1. `FileRuntimeConfigRepository`
2. `JdbcRuntimeConfigRepository`

这样既能快速启动，也能平滑走向生产治理。

当前骨架实现：

- 已提供 `FileRuntimeConfigRepository`
- 默认配置文件路径为 `data/runtime-config/runtime-config.json`

## 10. SSE 事件模型

前端对长期任务的体验依赖事件设计，建议统一事件协议。

建议事件类型：

- `run.started`
- `token.delta`
- `tool.call.started`
- `tool.call.completed`
- `subtask.started`
- `subtask.updated`
- `approval.required`
- `artifact.created`
- `memory.scheduled`
- `run.completed`
- `run.failed`

当前骨架实现：

- 已支持基于 replay sink 的线程事件流
- 已在审批等待、运行开始、运行完成、运行失败时发出事件
- 已在 run 级流式执行过程中发出真实 `token.delta`
- 已在 lead agent 发起工具调用时发出 `tool.call.started`
- 已在工具结果回流到消息流时发出 `tool.call.completed`

事件载荷要求：

- 必须带 `threadId`
- 必须带 `runId`
- 当前 `RunEventEnvelope` 只冻结 `threadId / runId / eventType / payload` 四个字段，尚未引入统一 `timestamp`
- `step / node` 当前也尚未进入统一对外契约；如需前端精细渲染，应通过 `payload` 中的工具或子任务信息补充

当前 payload 语义：

- `token.delta`：当前为单段文本字符串，来源于流式 assistant message 的 `text`
- `tool.call.started`：当前为 tool call 列表，包含 `id / type / name / arguments`
- `tool.call.completed`：当前为 tool response 列表
- `run.completed`：当前为完整 `ThreadStateSnapshot`
- `run.failed`：当前为最小错误对象，至少包含 `message`

## 11. 子任务执行设计

DeerFlow 的 subagent 不是简单直接调用另一个 Agent，而是后台任务执行模式。Java 版建议：

- 对内定义 `SubTaskExecutor`。
- 对外暴露一个 `task` 工具给 Lead Agent。
- `task` 工具提交任务后立即返回 `taskId`。
- 主线程可轮询/等待结果，也可继续做其他决策。

技术实现建议：

- MVP：应用内线程池 + 状态表。
- 增强版：队列化执行，可切换到 MQ/调度器。

当前骨架实现：

- 已提供应用内 `SubTaskExecutor`，默认使用应用内异步执行器运行子任务。
- 子任务状态会持久化到 `data/threads/{threadId}/metadata/subtasks/`。
- `task` 工具当前支持 `submit`、`status`、`cancel`、`retry` 四种动作；`submit/retry` 可选择等待完成后直接返回结果。
- 子任务开始与完成/失败会发出 `subtask.started` / `subtask.updated` 事件。
- `task` 工具当前支持 `single / sequential / parallel` 三种模式，其中 `sequential` 基于 `SequentialAgent`，`parallel` 基于 `ParallelAgent`。
- 子任务当前已支持默认执行超时、运行中取消和失败/超时后的重试；相关状态会落到 `CANCELLED` / `TIMED_OUT` / `COMPLETED` / `FAILED`。
- `SupervisorAgent / LlmRoutingAgent` 的运行时路由集成暂未启用，后续可在现有子任务框架上继续扩展。

## 12. 安全设计

### 12.1 文件与路径安全

- 所有路径都必须基于线程根目录解析。
- 禁止 `..`、绝对路径逃逸、符号链接绕过。

### 12.2 Sandbox 安全

- 生产环境默认禁用本地直接执行。
- 命令执行要有限时、限资源、限目录、限环境变量注入。

### 12.3 工具安全

- 高风险工具必须支持审批。
- 工具调用记录入审计日志。

### 12.4 密钥安全

- 模型/MCP 凭证仅引用环境变量或 Secret Manager。
- 不将敏感值回显到前端。

## 13. 可观测性设计

建议接入：

- 结构化日志
- Micrometer 指标
- OpenTelemetry Trace
- 运行成本统计

关键监控项：

- run 成功率
- 平均首包时间
- tool 调用失败率
- subtask 超时率
- approval 等待时长
- checkpoint 写入失败率

## 14. 技术选型建议

- Java 17+
- Spring Boot 3.x
- Spring AI Alibaba
- Spring WebFlux
- PostgreSQL
- Redis
- Reactor
- Docker Sandbox Provider

说明：

- 当前仓库骨架以 Java 17 为基线，便于与现有本地环境对齐；若后续使用虚拟线程等能力，可再升级到 Java 21。
- 选 WebFlux 主要是为了更自然地实现 SSE、异步工具调用和长任务事件流。
- 如果团队已有成熟 MVC 体系，也可 Controller 层保留 MVC，但运行时建议仍使用 Reactor 风格封装。

## 15. 关键风险与缓解

| 风险 | 说明 | 缓解措施 |
| --- | --- | --- |
| ReactAgent 与平台状态耦合过深 | 后续难扩展 | 用 Graph 包住 ReactAgent，平台状态不直接散落在 Agent 代码里 |
| 文件系统与 checkpoint 不一致 | 删除/恢复异常 | 线程删除走统一服务，先标记再清理 |
| 子任务执行失控 | 长任务拖垮实例 | 子任务限并发、限超时、可取消 |
| MCP 热加载抖动 | 影响正在运行任务 | 新旧配置双缓冲，运行中 task 绑定快照 |
| 记忆抽取成本高 | 拉高模型费用 | 异步去抖、阈值控制、独立小模型 |

## 16. 落地结论

推荐的 Java DeerFlow backend 结构是：

- 一个 Spring Boot 应用承接全部 backend 能力。
- 一个 Graph 工作流承接长任务状态机。
- 一个 ReactAgent 承接推理循环。
- 一组平台模块承接 DeerFlow 真正有价值的工程能力。

## 17. 参考资料

- [DeerFlow backend 与 Spring AI Alibaba 能力分析](./01-analysis.md)
- [Java DeerFlow Backend 需求文档](./02-requirements.md)
