一、Agent 平台级完整目录结构（总览）

agent-platform/
├── README.md
├── pyproject.toml / requirements.txt
├── docker/
│   ├── Dockerfile
│   └── docker-compose.yml
├── configs/
│   ├── system.yaml
│   ├── llm.yaml
│   ├── mcp.yaml
│   ├── memory.yaml
│   ├── security.yaml
│   └── budgets.yaml
│
├── apps/
│   ├── api-server/              # 对外 API（HTTP / gRPC）
│   ├── worker/                  # Agent Runtime Worker
│   └── scheduler/               # 定时 / 事件触发
│
├── core/                        # Agent 核心逻辑（最重要）
│   ├── agent/
│   │   ├── agent_loop.py
│   │   ├── state.py
│   │   └── lifecycle.py
│   │
│   ├── planner/
│   │   ├── base.py
│   │   ├── llm_planner.py
│   │   ├── schema.py
│   │   └── prompts/
│   │
│   ├── executor/
│   │   ├── executor.py
│   │   ├── step_runner.py
│   │   ├── retry.py
│   │   └── validation.py
│   │
│   ├── observation/
│   │   ├── parser.py
│   │   ├── error_classifier.py
│   │   └── models.py
│   │
│   ├── critic/
│   │   ├── rule_critic.py
│   │   ├── llm_critic.py
│   │   ├── decision.py
│   │   └── policies/
│   │
│   ├── memory/
│   │   ├── working.py
│   │   ├── episodic.py
│   │   ├── long_term.py
│   │   ├── retriever.py
│   │   └── writer.py
│   │
│   └── audit/
│       ├── logger.py
│       ├── models.py
│       └── storage.py
│
├── mcp/
│   ├── client/
│   │   ├── mcp_client.py
│   │   ├── tool_registry.py
│   │   └── permission.py
│   └── servers/
│       ├── search/
│       ├── database/
│       ├── code/
│       └── custom/
│
├── llm/
│   ├── base.py
│   ├── openai.py
│   ├── anthropic.py
│   ├── local.py
│   └── context_builder.py
│
├── tools/                       # 非 MCP 本地工具（少量）
│   ├── formatters/
│   └── validators/
│
├── memory_store/
│   ├── postgres/
│   ├── vector/
│   └── kv/
│
├── security/
│   ├── sandbox.py
│   ├── redaction.py
│   └── access_control.py
│
├── tests/
│   ├── planner/
│   ├── executor/
│   ├── critic/
│   ├── memory/
│   └── integration/
│
└── scripts/
    ├── bootstrap.py
    ├── migrate.py
    └── replay_task.py


⸻

二、顶层设计原则（先理解这个）

这套结构遵循 5 个硬原则：

1️⃣ LLM ≠ 核心逻辑
2️⃣ Executor / Memory / Audit 是平台能力
3️⃣ Planner / Critic 是可替换策略
4️⃣ MCP 是工具唯一入口
5️⃣ 任何行为必须可回放

⸻

三、核心目录逐层讲解（重点）

⸻

1️⃣ apps/ —— 运行形态（入口层）

apps/
├── api-server/
├── worker/
└── scheduler/

为什么要拆？

因为生产里 Agent 有 3 种运行方式：

模块	作用
api-server	同步请求（Chat / API）
worker	异步 Agent 执行
scheduler	Cron / Event

📌 Agent 平台 ≠ Chatbot

⸻

2️⃣ core/agent/ —— Agent 状态机

core/agent/
├── agent_loop.py
├── state.py
└── lifecycle.py

agent_loop.py（核心）

Planner
 → Executor
 → Observation
 → Critic
 → Memory
 → Audit

📌 这里不允许写 prompt，不允许写 tool

⸻

3️⃣ core/planner/ —— 计划系统

planner/
├── base.py
├── llm_planner.py
├── schema.py
└── prompts/

设计要点
	•	Planner 输出 严格 schema
	•	prompts 只是 Planner 的一种实现
	•	Planner 可替换（LLM / 规则 / 模板）

⸻

4️⃣ core/executor/ —— 最重要的确定性系统

executor/
├── executor.py
├── step_runner.py
├── retry.py
└── validation.py

Executor 的铁律

❌ 不准用 LLM
❌ 不准写 prompt
❌ 不准自动推理

📌 Executor 是 Agent 的“肌肉和骨骼”

⸻

5️⃣ mcp/ —— Agent 的“操作系统层”

mcp/
├── client/
│   ├── mcp_client.py
│   ├── tool_registry.py
│   └── permission.py
└── servers/

设计哲学

Agent 永远不知道 Tool 的实现

MCP 解决的是：
	•	工具发现
	•	Schema
	•	权限
	•	隔离

⸻

6️⃣ core/memory/ —— Agent 的人格系统

memory/
├── working.py
├── episodic.py
├── long_term.py
├── retriever.py
└── writer.py

为什么拆这么细？

因为 读策略 ≠ 写策略
	•	retriever：什么时候查
	•	writer：什么时候存

📌 这是 Agent 会不会“越用越聪明”的关键

⸻

7️⃣ core/critic/ —— 决策刹车系统

critic/
├── rule_critic.py
├── llm_critic.py
├── decision.py
└── policies/

双 Critic 的工程意义

Critic	作用
Rule	安全 / 合规
LLM	质量 / 偏离

📌 任何“继续”都必须被允许

⸻

8️⃣ core/audit/ —— 兜底与责任

audit/
├── logger.py
├── models.py
└── storage.py

审计设计目标
	•	回放
	•	Debug
	•	合规
	•	责任归因

⸻

9️⃣ llm/ —— 模型适配层

llm/
├── base.py
├── minimax.py
├── openai.py
├── anthropic.py
├── local.py
└── context_builder.py

关键点
	•	Context Builder 独立
	•	不把 memory 直接塞 prompt
	•	支持多模型并存

⸻

🔟 security/ —— 生产必需但常被忽略

security/
├── sandbox.py
├── redaction.py
└── access_control.py

📌 这是 Agent 真正能进生产的分界线

⸻

四、为什么这个结构「能活 3 年」

✔ Planner / Executor / MCP 解耦
✔ Memory & Audit 一等公民
✔ 可多 Agent / 多租户
✔ 可加 RL / Reflection / Self-improve
✔ 可无痛换模型