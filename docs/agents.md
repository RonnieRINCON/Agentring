# Connecting agents

There are two directions, and you can use both:

1. **Your agent reaches you.** Claude Code, OpenClaw, n8n or a script messages your
   phone, waits for your reply, or calls you. A voice AI talks to you for the
   agent and hands it the transcript. Use the [MCP server](#mcp-server-agentring-mcp),
   the CLI or the relay's REST API.
2. **You reach your agents by phone.** On a call (one the agent placed, or one you
   made with "Call agent" in the app), the voice AI can ask your agents for help.
   Each agent in [`agents.yaml`](#agentsyaml) is a tool for it, through
   `consult_agent`, and so are your MCP servers.

## MCP server (`agentring mcp`)

`agentring mcp` is an MCP server over stdio. Add it to any MCP client:

```bash
claude mcp add agentring -- agentring mcp          # Claude Code
```

```json
{ "mcpServers": { "agentring": { "command": "agentring", "args": ["mcp"] } } }
```

The second form is the usual `mcpServers` config that most clients read. It uses
the daemon's config (`agentring init`), so run `agentring init` and
`agentring pair` first.

| Tool | What it does |
|---|---|
| `send_message(text, reply_to?)` | Sends a message to the phone (a notification you can reply to inline). Returns `msg_id`. |
| `get_replies(wait_s?)` | Your new replies, each one returned once. `wait_s` waits up to that long for one (max 3600). |
| `call(reason, context?, urgent?)` | The phone rings. When you answer, the voice AI says why it's calling, talks with you, and hangs up when you're done. Returns the transcript, how long the call lasted, why it ended and which tools were used. Blocks until the call ends. Needs the `[voice]` extra and `GEMINI_API_KEY`. |
| `status()` | Whether the relay is reachable and which phones are paired. |

`context` is everything the voice AI knows about the agent's work: put in it what
to tell you, what to ask and what not to decide. If `~/.agentring/agents.yaml` exists,
the voice AI on these calls can also consult your agents.

The server is a small JSON-RPC loop with no MCP SDK and no extra dependencies
(see [architecture](architecture.md#accepted-deviations)). It does not support
Streamable HTTP. HTTP-only clients such as n8n use the REST API (below).

## agents.yaml

`~/.agentring/agents.yaml` (or `--agents PATH`) lists what the voice AI may use
during a call:

```yaml
agents:
  claude:
    description: Claude Code in my web app's repo. Code questions, CI status, deploys.
    command: [claude, -p, "{task}"]
    cwd: ~/code/webapp
    timeout_s: 600          # hard limit; the process is killed after it (default 600)
  ops:
    description: My ops scripts. Disk, backups, service status.
    command: [~/bin/ops-agent]   # no "{task}": the task arrives on stdin
    env: {OPS_PROFILE: prod}

mcp:                         # MCP servers over Streamable HTTP; their tools go straight to the voice AI
  - url: http://127.0.0.1:8787/mcp
    tools: [status, logs]    # allowlist; omit it to expose all of the server's tools
    headers: {Authorization: Bearer ...}
```

- **`command` is an argv list, never a shell string** (a string is rejected). The
  task comes from a voice model, so it is only ever one argument: `{task}` is
  replaced inside the argument that contains it, and if no argument has it, the
  task goes to stdin. The agent's stdout is the answer. A non-zero exit is an
  error, with the end of stderr.
- The agent runs with your environment plus `env` and `AGENTRING_CALL_ID`, in `cwd`.
- **`description` matters**: the voice AI picks the agent by it.
- Over the phone, expose only what the caller should be able to do: use
  allowlists, and agents whose permissions you'd accept from someone holding your
  phone. The phone is paired to your relay, but the voice AI acts on what it hears.

Check the file and try an agent without a call:

```bash
agentring agents                       # lists them; errors if the file is wrong
agentring agents ask claude "Is CI green on main?"
```

Then use it on calls:

```bash
agentring serve --ai                   # answers "Call agent" from the app
agentring call "Deploy needs your OK" --ai
```

### How a consultation goes

`consult_agent(agent, task)` waits up to 20 s. If the agent answers in that time,
the voice AI gets the answer right away. If not, it hears that the agent is working
on it, tells you, and the call carries on. When the answer arrives, the voice AI gets
it as a note and tells you. If the call has already ended, the answer is sent to
your phone as a message. Each agent works on one task at a time; a second request
for a busy agent is refused, so the model asks again later.

From Python, `Consultations(agents, answer_within_s=…, prompts=ConsultPrompts(…))`
builds the tool (`.tool()`) for a `VoiceAgent`. Any tool of your own can do the same
"answer later" trick: `current_session()` returns the call, and
`session.notify(text, respond=True)` speaks up when you are done.

## REST API (n8n, scripts)

Every action is one HTTPS request to your relay with the daemon's `ak_…` API key
(`agentring status --json` prints the relay URL; the key is in
`~/.agentring/.env`). See [`protocol/relay.openapi.yaml`](../protocol/relay.openapi.yaml):

```bash
curl -s https://relay.example.com/v1/messages -H "Authorization: Bearer $AGENTRING_API_KEY" \
  -H 'content-type: application/json' -d '{"text": "Backup finished"}'
```

Replies come from `GET /v1/messages?after=<msg_id>`, or live over the WebSocket. A
call over plain REST only rings the phone: the voice needs a worker in the room,
which is the daemon (`agentring call --ai`, or the `call` MCP tool). See
[`examples/`](../examples) for an n8n workflow.
