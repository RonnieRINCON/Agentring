"""Voice for agentring calls (extra: agentring[voice]).

    from agentring.voice import VoiceAgent, CallInfo, GeminiLive, Tool

    agent = VoiceAgent(lambda: GeminiLive(api_key), "You are my assistant…", tools=[Tool(...)])
    result = await agent.run(CallInfo(call_id, reason), join, relay=client)
"""

from .backend import RealtimeBackend, ToolResult
from .gemini import GeminiLive
from .inbound import accept_incoming, is_incoming_call
from .session import CallInfo, CallResult, CallSession, Prompts, VoiceAgent
from .tools import McpClient, Tool, ToolCall, mcp_tools

__all__ = [
    "CallInfo", "CallResult", "CallSession", "GeminiLive", "McpClient", "Prompts", "RealtimeBackend", "Tool",
    "ToolCall", "ToolResult", "VoiceAgent", "accept_incoming", "is_incoming_call", "mcp_tools",
]
