# Java DeerFlow Backend 开发计划（Codex 任务列表版）

## 1. 文档目的

本文将开发计划改写为适合个人结合 Codex 持续推进的任务列表格式。目标不是做团队排班，而是形成一份可以按顺序执行、逐项勾选、随时续跑的 backlog。

## 2. 使用约定

- `[ ]` 未开始
- `[~]` 进行中
- `[x]` 已完成
- `[!]` 阻塞或需人工决策

执行建议：

- 同一时间只保留 1 个 `[~]` 任务，避免上下文过于分散。
- 每完成 1 个任务，都同步补最小验证结果和相关文档。
- 优先按 `P0 -> P1 -> P2` 顺序推进，不建议跳过基础设施直接做高级能力。

## 3. 总体节奏

适合个人推进的推荐顺序：

- `P0`：先打通可运行链路，做出一个能跑、能恢复、能流式输出的最小 backend。
- `P1`：补平台层，让系统具备上传、产物、MCP、Skills 等 DeerFlow 风格能力。
- `P2`：补高级运行能力，包括子任务、长期记忆、审批恢复增强。
- `P3`：最后做生产化，包括安全、观测、隔离、压测和部署。

如果按“个人 + Codex”模式持续推进，建议按 6 到 10 周预期管理，不要按多人并行节奏估算。

## 4. 当前任务列表

### P0：项目骨架与技术验证

- [x] P0-01 初始化 Spring Boot + Spring AI Alibaba + WebFlux 工程
  产出：基础工程、配置模板、健康检查接口。
  完成标准：应用可启动，`/actuator/health` 正常返回。

- [x] P0-02 跑通 `ReactAgent + Graph + Checkpointer` 最小样例
  产出：一个可按 `threadId` 连续运行两轮的 demo。
  完成标准：同一 `threadId` 第二轮运行能看到第一轮上下文。

- [x] P0-03 跑通 SSE 输出链路
  产出：最小流式接口和事件模型草案。
  完成标准：前端或 curl 能连续接收到 token/event 流。

- [x] P0-04 验证本地工具与一个 MCP 工具接入
  产出：本地 tool demo、MCP tool demo、统一 tool 注册方式。
  完成标准：Lead Agent 能同时调用一个 built-in tool 和一个 MCP tool。

- [x] P0-05 冻结首版状态模型
  产出：`thread/run/messages/workspace/uploads/artifacts/todos/approval` 等核心状态定义。
  完成标准：架构文档和代码中的状态模型一致。

- [x] P0-06 冻结首版 API 与 SSE 事件协议
  产出：接口清单、事件名称、基础 payload 结构。
  完成标准：后续 P1 之前不再大改主接口前缀和核心事件名。

### P1：核心运行时

- [x] P1-01 实现 `ThreadWorkspaceService`
  产出：线程目录结构、创建/删除/解析能力。
  完成标准：自动生成 `workspace/uploads/outputs`，删除线程时能一并清理。

- [x] P1-02 实现 `RuntimeGraphFactory`
  产出：`PrepareThread -> AssembleContext -> RunLeadAgent -> PersistArtifacts` 的基础图。
  完成标准：能完整跑通一次多步骤任务。

- [x] P1-03 实现 `LeadAgentFactory`
  产出：统一创建 ReactAgent、绑定 tools、hooks、interceptors 的工厂。
  完成标准：主 Agent 初始化逻辑不散落在 Controller 中。

- [x] P1-04 实现 Runtime API 第一版
  产出：`POST /api/threads`、`POST /api/threads/{threadId}/runs`、`GET /api/threads/{threadId}`、`DELETE /api/threads/{threadId}`。
  完成标准：能创建线程、运行任务、查询状态、删除线程。

- [x] P1-05 实现 `LocalProcessSandboxProvider`
  产出：本地命令执行、目录列举、文件读写、替换能力。
  完成标准：Agent 可在限定线程目录中完成基础文件操作。

- [x] P1-06 实现路径安全校验
  产出：虚拟路径到真实路径的统一解析器。
  完成标准：禁止 `..`、绝对路径逃逸、线程间越权访问。

- [x] P1-07 实现 Run 状态机
  产出：`RUNNING/WAITING_APPROVAL/FAILED/COMPLETED` 等状态流转。
  完成标准：运行中断、失败、完成都能被正确记录和查询。

- [ ] P1-08 实现最小验证集
  产出：线程创建/运行/恢复/删除的集成测试。
  完成标准：核心运行时主链路有自动化验证。

### P2：平台能力补齐

- [ ] P2-01 实现 `RuntimeConfigRepository`
  产出：动态配置抽象和文件版实现。
  完成标准：模型、MCP、Skills 开关可脱离代码硬编码。

- [ ] P2-02 实现 `ModelRegistryService`
  产出：模型注册、查询、能力标签定义。
  完成标准：`GET /api/models` 可返回模型列表与能力信息。

- [ ] P2-03 实现 `McpConfigService`
  产出：MCP 配置查询、更新、启停和热加载。
  完成标准：修改配置后后续运行可生效，无需重启。

- [ ] P2-04 实现 `SkillRegistryService`
  产出：技能发现、详情、启用、禁用。
  完成标准：启用技能后，下一轮运行能感知到技能上下文。

- [ ] P2-05 实现 `UploadService`
  产出：多文件上传、列表、删除。
  完成标准：上传文件后，Agent 下一轮能看到文件和虚拟路径。

- [ ] P2-06 实现文档转 Markdown 流程
  产出：PDF、PPT、Excel、Word 的转换适配层。
  完成标准：转换成功时生成 Markdown 派生文件，失败时保留原文件并返回错误信息。

- [ ] P2-07 实现 `ArtifactService`
  产出：产物索引、元数据记录、下载与预览接口。
  完成标准：线程内生成的文件可通过 HTTP 访问。

- [ ] P2-08 接入 `SummarizationHook` 与 `TodoListInterceptor`
  产出：上下文压缩与任务规划能力。
  完成标准：复杂任务能输出待办列表，长上下文不会无限膨胀。

- [ ] P2-09 实现审批恢复基础链路
  产出：`approval.required` 事件、审批提交接口、恢复执行接口。
  完成标准：任务可在审批后从中断点继续执行。

- [ ] P2-10 补齐平台 API 集成测试
  产出：模型、MCP、Skills、Uploads、Artifacts 测试用例。
  完成标准：平台主链路具备自动化回归能力。

### P3：高级运行能力

- [ ] P3-01 实现 `MemoryStore` 抽象
  产出：长期记忆存储接口与默认实现。
  完成标准：支持按用户维度读写长期记忆事实。

- [ ] P3-02 实现 `MemoryExtractorJob`
  产出：运行后异步抽取用户事实、偏好、上下文。
  完成标准：记忆抽取失败不影响主任务完成。

- [ ] P3-03 实现记忆注入策略
  产出：运行前记忆检索、筛选、注入规则。
  完成标准：跨线程运行可复用长期记忆。

- [ ] P3-04 实现 `SubTaskExecutor`
  产出：子任务提交、状态跟踪、结果收集能力。
  完成标准：主 Agent 可通过 `task` 工具委派子任务。

- [ ] P3-05 接入 `SupervisorAgent / ParallelAgent / SequentialAgent`
  产出：多 Agent 编排能力。
  完成标准：至少支持一个串行和一个并行编排场景。

- [ ] P3-06 实现子任务超时、取消、重试机制
  产出：子任务生命周期管理。
  完成标准：超时子任务不会无限占用资源。

- [ ] P3-07 实现标题生成与建议问题生成
  产出：post-run processors。
  完成标准：任务完成后可生成标题和后续建议问题。

- [ ] P3-08 增强审批/澄清工作流
  产出：审批通过、拒绝、补充说明三种路径。
  完成标准：不同审批结果能驱动不同后续执行分支。

- [ ] P3-09 补齐高级能力 E2E 场景
  产出：子任务、记忆、审批恢复端到端验证。
  完成标准：高级运行能力至少覆盖 3 个真实场景测试。

### P4：生产化与稳定性

- [ ] P4-01 实现 `ContainerSandboxProvider`
  产出：容器化 sandbox 方案。
  完成标准：生产环境可禁用本地直接执行。

- [ ] P4-02 接入鉴权与审计日志
  产出：关键 API 鉴权、高风险工具审计。
  完成标准：工具调用和关键配置变更有审计记录。

- [ ] P4-03 接入 Micrometer 指标与基础告警
  产出：run 成功率、首包时间、tool 失败率等指标。
  完成标准：关键指标可在监控系统中查看。

- [ ] P4-04 接入 Trace 与结构化日志
  产出：线程执行链路、工具调用链路的追踪能力。
  完成标准：能通过日志和 trace 快速定位失败节点。

- [ ] P4-05 实现限流、超时、配额
  产出：实例级和线程级保护策略。
  完成标准：长任务和异常任务不会轻易拖垮实例。

- [ ] P4-06 完成长任务压测与恢复演练
  产出：压测报告、故障演练记录。
  完成标准：至少验证一次实例重启后的线程恢复能力。

- [ ] P4-07 输出部署文档与运维手册
  产出：部署步骤、配置说明、常见问题处理文档。
  完成标准：可按文档独立完成部署与基本排障。

## 5. 建议先做的 5 个任务

如果现在立刻开工，推荐按下面顺序推进：

- [x] P0-01 初始化 Spring Boot + Spring AI Alibaba + WebFlux 工程
- [x] P0-02 跑通 `ReactAgent + Graph + Checkpointer` 最小样例
- [x] P0-03 跑通 SSE 输出链路
- [x] P0-05 冻结首版状态模型
- [ ] P1-01 实现 `ThreadWorkspaceService`

这 5 个任务做完后，项目会从“设计阶段”进入“可执行雏形阶段”。

## 6. Definition of Done

每个任务只有同时满足以下条件，才建议从 `[ ]` 改为 `[x]`：

- 代码已落地。
- 至少完成一条手工验证或自动化验证。
- 相关接口、状态或配置说明已同步到文档。
- 没有遗留阻断后续任务的已知问题；如果有，需要显式转成新的 `[ ]` 或 `[!]` 任务。

## 7. 可能需要单独建卡的阻塞项

- [ ] 是否要求兼容 DeerFlow 现有前端接口前缀和事件协议
- [ ] 文档转 Markdown 的技术选型是否已有既定组件
- [ ] 长期记忆首版采用 PostgreSQL、Redis 还是向量库
- [ ] 生产环境 Sandbox 是否强制容器化
- [ ] 是否要在首版就支持多模型供应商

这些问题不一定阻塞 PoC，但会影响 P2 之后的设计稳定性。

## 8. 关联文档

- [DeerFlow backend 与 Spring AI Alibaba 能力分析](./01-analysis.md)
- [Java DeerFlow Backend 需求文档](./02-requirements.md)
- [Java DeerFlow Backend 架构设计](./03-architecture.md)
