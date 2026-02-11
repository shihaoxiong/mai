"""MAI Agent - Conversational Session Example

Demonstrates session management and goal inference.
"""

import asyncio
from mai_agent import create_agent
from mai_agent.config import get_settings


async def example_conversation():
    """Example: Multi-turn conversation with goal inference"""
    print("=" * 60)
    print("MAI Agent - Conversational Session Example")
    print("=" * 60)

    agent = await create_agent(
        name="mai-agent",
        llm_api_key="your-api-key-here"
    )

    print("\n🤖 Agent initialized!")
    print(f"Session Management: {agent.session_manager.get_statistics()}")

    session_id = None

    print("\n" + "-" * 40)
    print("📝 Conversation Start")
    print("-" * 40)

    conversation = [
        "请帮我读取项目根目录的 README.md 文件",
        "好的，请总结一下项目的主要功能",
        "现在请帮我查看 pyproject.toml 中的依赖"
    ]

    for i, user_message in enumerate(conversation, 1):
        print(f"\n👤 User [{i}]: {user_message}")

        result = await agent.chat(
            session_id=session_id,
            user_message=user_message
        )

        session_id = result["session_id"]

        print(f"🤖 Assistant [{result['status']}]:")
        print(f"   Goal: {result.get('goal', 'N/A')}")
        print(f"   Message: {result['message'][:200]}...")

        stats = agent.get_statistics()
        print(f"   📊 Stats: {stats['total_tasks']} tasks, {stats['sessions']['active_sessions']} active sessions")

    print("\n" + "-" * 40)
    print("📊 Final Statistics")
    print("-" * 40)
    stats = agent.get_statistics()
    print(f"Total tasks executed: {stats['total_tasks']}")
    print(f"Successful: {stats['successful_tasks']}")
    print(f"Failed: {stats['failed_tasks']}")
    print(f"Success rate: {stats['success_rate']:.1f}%")

    sessions = await agent.session_manager.list_sessions()
    print(f"\nSessions: {len(sessions)}")
    for s in sessions:
        print(f"  - {s['session_id'][:8]}... | Messages: {s['message_count']} | Has Goal: {s['has_goal']}")

    await agent.close()
    print("\n✅ Agent closed.")


async def example_multi_session():
    """Example: Multiple independent sessions"""
    print("\n" + "=" * 60)
    print("MAI Agent - Multi-Session Example")
    print("=" * 60)

    agent = await create_agent()

    sessions_data = [
        {"id": "session-1", "message": "请帮我创建一个测试文件 test.txt，内容是 Hello World"},
        {"id": "session-2", "message": "请帮我列出当前目录的文件"},
        {"id": "session-3", "message": "帮我读取某个配置文件"}
    ]

    for session_data in sessions_data:
        print(f"\n🆕 Starting session: {session_data['id']}")
        result = await agent.chat(
            session_id=session_data["id"],
            user_message=session_data["message"]
        )
        print(f"   Status: {result['status']}")
        print(f"   Session: {result['session_id'][:8]}...")

    print("\n📊 All Sessions:")
    all_sessions = await agent.session_manager.list_sessions(include_completed=True)
    for s in all_sessions:
        print(f"  - {s['session_id']} | State: {s['state']} | Messages: {s['message_count']}")

    await agent.close()


async def example_goal_inference():
    """Example: Goal inference from conversation"""
    print("\n" + "=" * 60)
    print("MAI Agent - Goal Inference Example")
    print("=" * 60)

    agent = await create_agent(
        name="mai-agent",
        llm_api_key="your-api-key-here"
    )

    conversation = [
        "我想了解一下这个项目的结构",
        "用终端命令查看一下项目文件",
        "能详细说明每个目录的作用吗",
        "好的，就这些，任务完成了"
    ]

    current_goal = None

    for msg in conversation:
        result = await agent.chat(
            session_id=None,
            user_message=msg
        )

        if result.get("goal") != current_goal:
            if current_goal:
                print(f"\n🔄 Goal changed: {current_goal[:50]}... -> {result['goal'][:50]}...")
            else:
                print(f"\n🎯 Goal inferred: {result.get('goal', 'N/A')}")
            current_goal = result.get("goal")

        print(f"👤: {msg}")
        print(f"🤖: {result['message'][:500]}...")

    await agent.close()


async def simple_demo():
    """Simple demo without API key"""
    print("=" * 60)
    print("MAI Agent - Simple Demo")
    print("=" * 60)

    agent = await create_agent()

    print(f"Agent created!")
    print(f"State: {agent.get_state()}")
    print(f"Statistics: {agent.get_statistics()}")

    stats = agent.session_manager.get_statistics()
    print(f"Sessions: {stats}")

    await agent.close()


if __name__ == "__main__":
    print("MAI Agent - Conversational AI System")
    print("=" * 60)

    asyncio.run(example_goal_inference())

    print("\n" + "=" * 60)
    print("To run with real LLM:")
    print("  1. Set MINIMAX_API_KEY environment variable")
    print("  2. Run: asyncio.run(example_conversation())")
    print("=" * 60)
