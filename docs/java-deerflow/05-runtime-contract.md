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
- `messages`
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

#### `ThreadMessage`

- `role`
- `content`

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

### 3.3 当前 payload 语义

当前实现里，各事件 payload 约定如下：

- `run.started`
  payload：最小运行状态对象，当前至少包含 `status`
- `token.delta`
  payload：字符串；来源于流式 assistant message 的 `text`
- `tool.call.started`
  payload：tool call 列表；当前元素至少包含 `id`、`type`、`name`、`arguments`
- `tool.call.completed`
  payload：tool response 列表
- `approval.required`
  payload：`ApprovalState`
- `run.completed`
  payload：`ThreadStateSnapshot`
- `run.failed`
  payload：最小错误对象，当前至少包含 `message`

### 3.4 当前边界

- run 级 SSE 当前已经接入真实流式输出链路，不再在 `run.completed` 后补发伪造 `token.delta`
- run 级流式依赖底层 `ChatModel.stream(...)`；如果 provider 未实现流式接口，当前会快速失败，而不是自动降级到假流式
- 多 `Generation` 归一化目前尚未进入 runtime 对外契约；如果 provider 在单轮或单个流式 chunk 中返回多个候选 generation，底层 agent 仍可能只消费其中一条

## 4. P0 的冻结边界

当前冻结的是“命名与结构”，不是最终完整语义。

这意味着：

- 后续可以补充字段，但尽量不要随意改已有字段名
- 后续可以增加事件，但不要轻易修改已有 wire name
- 在真正 Runtime API 落地时，应优先复用这些 contract 类型

## 5. 当前验证

已通过以下验证：

- `RuntimeContractTests`：验证状态模型与事件名
- `ThreadRuntimeControllerTests`：验证 run 级 SSE 使用统一事件协议

## 6. 关联文档

- [Java DeerFlow Backend 架构设计](./03-architecture.md)
- [Java DeerFlow Backend 开发计划（Codex 任务列表版）](./04-development-plan.md)
