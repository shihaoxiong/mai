# Java DeerFlow Backend P0 运行时契约

## 1. 目标

本文用于冻结 `P0-05` 和 `P0-06` 的首版运行时契约，避免后续在进入真正 Runtime API 开发后频繁改动状态字段、事件名和基础对象结构。

本契约分两部分：

- 线程状态模型
- SSE 事件模型

对应代码位置：

- `src/main/java/com/mai/deerflow/backend/runtime/contract`

## 2. 状态模型

### 2.1 顶层对象

首版统一使用 `ThreadStateSnapshot` 表示线程当前聚合状态，包含：

- `threadId`
- `runId`
- `runStatus`
- `workspace`
- `uploads`
- `artifacts`
- `todos`
- `approval`
- `suggestions`
- `title`

### 2.2 关键类型

#### `RunStatus`

- `IDLE`
- `RUNNING`
- `WAITING_APPROVAL`
- `WAITING_CLARIFICATION`
- `FAILED`
- `COMPLETED`

#### `WorkspaceState`

- `workspacePath`
- `uploadsPath`
- `outputsPath`

#### `UploadRef`

- `name`
- `originalVirtualPath`
- `markdownVirtualPath`

#### `ArtifactRef`

- `name`
- `virtualPath`
- `contentType`

#### `TodoItem`

- `id`
- `title`
- `status`

#### `TodoStatus`

- `PENDING`
- `IN_PROGRESS`
- `COMPLETED`
- `BLOCKED`

#### `ApprovalState`

- `approvalId`
- `status`
- `reason`

#### `ApprovalStatus`

- `NONE`
- `WAITING`
- `APPROVED`
- `REJECTED`
- `NEEDS_CLARIFICATION`

## 3. SSE 事件模型

统一使用 `RunEventEnvelope<T>` 表示事件载荷，包含：

- `threadId`
- `runId`
- `eventType`
- `payload`

### 3.1 首版事件名

`RunEventType` 当前冻结如下 wire name：

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

### 3.2 约束

- SSE `event` 名称直接使用 `RunEventType.wireName()`
- SSE `id` 推荐格式：`{runId}:{eventType}`
- 所有事件都必须带 `threadId` 和 `runId`

## 4. P0 的冻结边界

当前冻结的是“命名与结构”，不是最终完整语义。

这意味着：

- 后续可以补充字段，但尽量不要随意改已有字段名
- 后续可以增加事件，但不要轻易修改已有 wire name
- 在真正 Runtime API 落地时，应优先复用这些 contract 类型

## 5. 当前验证

已通过以下验证：

- `RuntimeContractTests`：验证状态模型与事件名
- `P0SseDemoControllerTests`：验证 SSE demo 使用统一事件协议

## 6. 关联文档

- [Java DeerFlow Backend 架构设计](./03-architecture.md)
- [Java DeerFlow Backend 开发计划（Codex 任务列表版）](./04-development-plan.md)
