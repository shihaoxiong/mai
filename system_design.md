生产级 Agent 系统设计文档（MCP + Memory）

架构主线

Planner
→ Executor
→ Tool（via MCP）
→ Observation
→ Dual Critic
→ Memory
→ Audit Log

⸻

1. 设计目标与边界

1.1 设计目标

构建一个满足以下条件的 Agent 系统：
	•	✅ 可长期运行（Stateful）
	•	✅ 可审计、可回放
	•	✅ 上下文不依赖 prompt 堆叠
	•	✅ 工具可插拔、模型可替换
	•	✅ 失败可控、风险可兜底

适用场景：
	•	企业自动化
	•	运维 / 数据分析
	•	AI Coding
	•	Agent-as-a-Service

⸻

1.2 非目标（明确排除）
	•	❌ 不做“全自治 AI”
	•	❌ 不让 LLM 直接操作关键系统
	•	❌ 不依赖单一模型能力
	•	❌ 不把 memory 塞进 prompt

⸻

2. 总体架构（加入 MCP & Memory）

┌────────────────────────┐
│      User / Event      │
└────────────┬───────────┘
             ▼
┌────────────────────────┐
│    Task Interpreter    │
│  (Goal Structuring)    │
└────────────┬───────────┘
             ▼
┌────────────────────────┐
│        Planner         │  ← LLM
│  (Plan Generation)     │
└────────────┬───────────┘
             ▼
┌────────────────────────┐
│        Executor        │  ← Deterministic
└────────────┬───────────┘
             ▼
┌────────────────────────┐
│      MCP Client        │
│  (Tool Abstraction)    │
└────────────┬───────────┘
             ▼
┌────────────────────────┐
│     Tool Providers     │
│  (Search / Code / DB)  │
└────────────┬───────────┘
             ▼
┌────────────────────────┐
│    Observation Layer   │
└────────────┬───────────┘
             ▼
┌──────────────┬──────────────┐
│   Critic-A   │   Critic-B   │
│   (Rules)    │   (LLM)      │
└──────┬───────┴───────┬──────┘
       ▼               ▼
┌────────────────────────┐
│        Memory          │
│  (Working / Episodic / │
│   Long-term)           │
└────────────┬───────────┘
             ▼
┌────────────────────────┐
│       Audit Log        │
└────────────────────────┘


⸻

3. MCP（Model Context Protocol）设计

3.1 MCP 在系统中的角色

MCP = Agent 与外部世界的“操作系统接口”

它解决的不是“能不能调用工具”，而是：
	•	工具发现
	•	工具 schema
	•	工具权限
	•	工具上下文隔离

⸻

3.2 MCP 架构定位

Executor
   ↓
MCP Client
   ↓
MCP Server
   ↓
Tool Provider

📌 Agent 永远不直接调用 Tool

⸻

3.3 MCP Tool 描述模型

{
  "name": "search_logs",
  "description": "Search application logs",
  "input_schema": {
    "query": "string",
    "time_range": "string"
  },
  "output_schema": {
    "logs": "array"
  },
  "side_effect": false,
  "permission": "read_only"
}


⸻

3.4 MCP 的工程价值

问题	MCP 解决
Tool 接口混乱	统一 schema
多模型协作	Context 标准化
安全	权限边界
可替换	Tool Provider 解耦

📌 MCP 是 Agent 系统的“总线”

⸻

4. Memory 系统设计（重点）

4.1 为什么 Memory 是一等公民

没有 Memory 的 Agent：
	•	每一轮都是“失忆的”
	•	无法长期演化
	•	成本极高

⸻

4.2 Memory 分层设计

Memory
├── Working Memory
├── Episodic Memory
└── Long-term Memory


⸻

4.3 Working Memory（短期）

特点
	•	生命周期 = 单次任务
	•	存当前 Plan、Observation

{
  "current_step": 2,
  "observations": [...],
  "intermediate_results": {...}
}

📌 不进数据库

⸻

4.4 Episodic Memory（任务级）

特点
	•	一次完整 Agent 执行
	•	可回放、可总结

{
  "task_id": "uuid",
  "goal": "...",
  "steps": [...],
  "outcome": "success",
  "summary": "..."
}

📌 用于：
	•	失败分析
	•	经验总结
	•	反思（Reflection）

⸻

4.5 Long-term Memory（长期）

特点
	•	稳定知识
	•	跨任务复用

存储方式

类型	技术
结构化	PostgreSQL
语义	Vector DB
配置	KV Store


⸻

4.6 Memory 写入策略（关键）

❌ 不要每一步都写向量库

推荐策略：

Only write when:
- Task finished
- Critic-B says "valuable"
- Explicit signal


⸻

5. Planner（结合 Memory）

Planner 输入：
	•	当前任务目标
	•	Episodic Memory 摘要
	•	Long-term Memory（检索后）

Planner 输出 不包含执行细节。

📌 Planner 不访问 Working Memory 原始数据

⸻

6. Executor（结合 MCP）

Executor 只做三件事：
	1.	校验 Plan Step
	2.	通过 MCP 调用 Tool
	3.	生成 Observation

Executor ≠ Agent
Executor = Workflow Engine


⸻

7. Observation Layer

Observation = Tool → Agent 的翻译层

{
  "step_id": 3,
  "status": "failed",
  "error_type": "timeout",
  "retryable": true,
  "raw_output_ref": "s3://..."
}

📌 Observation 是 Critic 和 Memory 的输入

⸻

8. Dual Critic（双评估器）

8.1 Critic-A（规则）
	•	权限
	•	步数
	•	预算
	•	MCP tool 白名单

❌ Fail → 立即 Stop

⸻

8.2 Critic-B（LLM）

输入：
	•	Goal
	•	Observation summary
	•	Memory context

输出：

{
  "decision": "continue | replan | stop",
  "confidence": 0.87,
  "reason": "Result incomplete"
}


⸻

9. Critic → Memory 的反馈回路

Critic-B
   ↓
Reflection
   ↓
Write Episodic / Long-term Memory

📌 这一步决定 Agent 会不会“变聪明”

⸻

10. Audit Log（终极兜底）

10.1 审计范围

层级	内容
Task	原始输入
Planner	Plan
Executor	每 step
MCP	Tool 调用
Critic	决策
Memory	写入


⸻

10.2 审计目标
	•	合规
	•	Debug
	•	责任追踪
	•	行为回放

⸻

11. 故障与降级

模块	降级
LLM	Rule-only
MCP	Mock Tool
Memory	Read-only
Critic-B	Critic-A


⸻

12. 技术选型总结

模块	推荐
Planner	minimax
Executor	Python
MCP	官方 MCP
Memory	Postgres + Vector DB
Critic	Rule + LLM
Audit	DB + Object Store


⸻

13. 架构哲学（你现在这个层级一定要记住）

Agent ≠ 模型
Agent = 受控状态机 + 不确定推理模块

	•	MCP 控制“能做什么”
	•	Executor 控制“怎么做”
	•	Critic 控制“该不该继续”
	•	Memory 决定“会不会成长”