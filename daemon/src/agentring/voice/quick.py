"""A ready-made voice agent from a few options (what `agentring call --ai` and `serve --ai` use)."""

from __future__ import annotations

from pathlib import Path

from .gemini import DEFAULT_MODEL, DEFAULT_VOICE, GeminiLive
from .session import VoiceAgent
from .tools import McpClient, Tool, mcp_tools

DEFAULT_INSTRUCTIONS = (
    "You are the user's AI agent talking to them on the phone. Speak like a person on a call: short, "
    "natural sentences, one idea at a time, in the user's language. Never read out ids, links or paths. "
    "Never claim you did or checked something unless a tool told you so in this call."
)


def read_instructions(value: str | None) -> str:
    """Inline text, or @path to read it from a file."""
    if not value:
        return DEFAULT_INSTRUCTIONS
    if value.startswith("@"):
        return Path(value[1:]).expanduser().read_text(encoding="utf-8")
    return value


async def build_agent(
    api_key: str,
    *,
    instructions: str | None = None,
    mcp: list[str] | None = None,
    allow: list[str] | None = None,
    model: str | None = None,
    voice: str | None = None,
) -> tuple[VoiceAgent, list[McpClient]]:
    """Gemini Live with the tools of the given MCP servers (only `allow` if given).
    Close the returned clients when done."""
    tools: list[Tool] = []
    clients: list[McpClient] = []
    for url in mcp or []:
        found, client = await mcp_tools(url, allow=allow)
        tools += found
        clients.append(client)
    text = read_instructions(instructions)
    agent = VoiceAgent(lambda: GeminiLive(api_key, model=model or DEFAULT_MODEL, voice=voice or DEFAULT_VOICE),
                       text, tools)
    return agent, clients
