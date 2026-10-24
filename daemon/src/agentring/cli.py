"""agentring command-line interface."""

from __future__ import annotations

import asyncio
import json
import logging
import re
import signal
import sys
import time
from datetime import datetime, timezone
from typing import Annotated, Any, Optional

import segno
import typer

from . import __version__
from .config import ConfigError, Settings, load_settings, write_settings
from .relay_client import RelayClient, RelayError
from .store import Store
from .sync import FatalSyncError, catch_up, run_ws

app = typer.Typer(
    help="Let your AI agent call and message your phone.",
    no_args_is_help=True,
    add_completion=False,
    pretty_exceptions_enable=False,
)
devices_app = typer.Typer(help="List or revoke paired phones.", no_args_is_help=False)
app.add_typer(devices_app, name="devices")

JsonOpt = Annotated[bool, typer.Option("--json", help="Machine-readable JSON output.")]


def _now_ms() -> int:
    return int(time.time() * 1000)


def _fail(msg: str, code: int = 1) -> typer.Exit:
    typer.secho(f"error: {msg}", fg=typer.colors.RED, err=True)
    return typer.Exit(code)


def _settings() -> Settings:
    try:
        return load_settings()
    except ConfigError as e:
        raise _fail(str(e)) from None


def _client(s: Settings) -> RelayClient:
    return RelayClient(s.relay_url, s.api_key)


def _emit_json(obj: Any) -> None:
    typer.echo(json.dumps(obj, ensure_ascii=False, indent=2))


def _fmt_ts(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000).strftime("%Y-%m-%d %H:%M:%S")


_DURATION = re.compile(r"^(\d+)\s*([smhd])$")


def parse_since(value: str, now_ms: int | None = None) -> int:
    """Accepts a relative duration (30s, 10m, 2h, 1d), epoch milliseconds, or an ISO-8601 datetime."""
    now_ms = now_ms if now_ms is not None else _now_ms()
    v = value.strip()
    m = _DURATION.match(v)
    if m:
        mult = {"s": 1, "m": 60, "h": 3600, "d": 86400}[m.group(2)]
        return now_ms - int(m.group(1)) * mult * 1000
    if v.isdigit():
        return int(v)
    try:
        dt = datetime.fromisoformat(v.replace("Z", "+00:00"))
    except ValueError:
        raise typer.BadParameter("use e.g. 10m, 2h, 1d, epoch ms, or 2026-10-23T10:00:00") from None
    if dt.tzinfo is None:
        dt = dt.astimezone()  # local time
    return int(dt.timestamp() * 1000)


@app.callback()
def _root(
    verbose: Annotated[bool, typer.Option("--verbose", "-v", help="Debug logging.")] = False,
) -> None:
    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        stream=sys.stderr,
    )
    logging.getLogger("httpx").setLevel(logging.WARNING)


@app.command()
def version() -> None:
    """Print the version."""
    typer.echo(__version__)


@app.command()
def init(
    relay: Annotated[str, typer.Option("--relay", help="Relay base URL, e.g. https://relay.example.com")],
    api_key: Annotated[str, typer.Option("--api-key", help="Relay API key (ak_...)")],
) -> None:
    """Configure the relay URL and API key (stored in ~/.agentring)."""
    if not relay.startswith(("https://", "http://")):
        raise _fail("--relay must be an http(s) URL")
    if not api_key.startswith("ak_"):
        raise _fail("--api-key must start with ak_")
    with RelayClient(relay, api_key) as c:
        try:
            st = c.status()
        except RelayError as e:
            raise _fail(str(e)) from None
    home = write_settings(relay, api_key)
    typer.echo(f"Connected to relay {st['relay_id']} (agent name: {st['agent_name']}). Config saved in {home}")


@app.command()
def pair(
    wait: Annotated[bool, typer.Option("--wait/--no-wait", help="Wait until the phone pairs.")] = True,
    json_out: JsonOpt = False,
) -> None:
    """Show a QR code to pair the Android app (single use, valid 10 minutes)."""
    s = _settings()
    with _client(s) as c:
        try:
            known = {d["device_id"] for d in c.list_devices()}
            tok = c.create_pairing_token()
        except RelayError as e:
            raise _fail(str(e)) from None

        if json_out:
            _emit_json(tok)
        else:
            qr = segno.make(tok["qr_payload"], error="m")
            qr.terminal(compact=True, border=2)
            typer.echo("\nScan this QR code with the agentring app (Pair → Scan QR).")
            typer.echo("No camera? Copy this pairing code into the app (Pair → Paste code):\n")
            typer.echo(f"  {tok['qr_payload']}\n")
            typer.echo(f"Valid until {_fmt_ts(tok['expires_at'])}, single use.")
        if not wait:
            return

        if not json_out:
            typer.echo("Waiting for the phone… (Ctrl+C to stop)")
        try:
            while _now_ms() < tok["expires_at"]:
                time.sleep(2)
                new = [d for d in c.list_devices() if d["device_id"] not in known]
                if new:
                    d = new[0]
                    if json_out:
                        _emit_json({"paired": True, "device": d})
                    else:
                        typer.secho(f"Paired with {d['name']} ({d['device_id']}) ✓", fg=typer.colors.GREEN)
                    return
        except KeyboardInterrupt:
            raise typer.Exit(130) from None
        except RelayError as e:
            raise _fail(str(e)) from None
        raise _fail("pairing token expired; run `agentring pair` again")


_TERMINAL = {"ended", "missed", "declined", "canceled"}


@app.command()
def call(
    reason: Annotated[str, typer.Argument(help="Why you're calling; shown on the phone (max 500 chars).")],
    urgent: Annotated[bool, typer.Option("--urgent", help="Ring like an alarm, bypassing Do Not Disturb if allowed.")] = False,
    agent_name: Annotated[Optional[str], typer.Option("--agent-name", help="Caller name shown for this call.")] = None,
    greeting: Annotated[
        Optional[str], typer.Option("--greeting", help="What the agent says when the user answers (default: the reason).")
    ] = None,
    voice: Annotated[Optional[str], typer.Option("--voice", help="macOS `say` voice for the greeting.")] = None,
    json_out: JsonOpt = False,
) -> None:
    """Call the phone. Rings until answered (30 s); on answer the agent greets and echoes you (M2 audio test)."""
    try:
        from .voice.echo import EchoWorker, synthesize
    except ImportError:
        raise _fail("voice support is not installed: install agentring with the [voice] extra") from None

    s = _settings()
    log = logging.getLogger("agentring")
    client = _client(s)

    async def run() -> dict:
        after = (await asyncio.to_thread(client.list_events, 0, 1))["last_seq"]
        try:
            created = await asyncio.to_thread(
                client.create_call, reason, priority="urgent" if urgent else "normal", agent_name=agent_name
            )
        except RelayError as e:
            raise _fail(str(e)) from None
        c, join = created["call"], created["join"]
        t0 = c["created_at"]
        call_id = c["call_id"]
        if not json_out:
            typer.echo(f"Calling… {call_id} (Ctrl+C to hang up)")

        text = greeting if greeting is not None else f"{c['agent_name']}. {reason}"
        pcm = await asyncio.to_thread(synthesize, text, voice)
        user_left = asyncio.Event()
        worker = EchoWorker(pcm, user_left)
        stop = asyncio.Event()
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            try:
                loop.add_signal_handler(sig, stop.set)
            except NotImplementedError:
                pass

        def say(line: str) -> None:
            if not json_out:
                typer.echo(f"  +{(_now_ms() - t0) / 1000:5.1f}s  {line}")

        # Join the room while the phone rings; the user shouldn't wait for us after answering.
        joining = asyncio.create_task(worker.start(join["livekit_url"], join["livekit_token"]))
        joined = False
        try:
            state = c["state"]
            while state not in _TERMINAL:
                if joining.done() and not joined:
                    joined = True
                    if joining.exception():
                        log.error("could not join the call's room: %s", joining.exception())
                        typer.secho("error: the agent could not join the call (LiveKit unreachable?)", fg=typer.colors.RED, err=True)
                        stop.set()
                    else:
                        say("agent in the room")
                if user_left.is_set() and not stop.is_set():
                    # The room emptied. In a group call another phone may still answer
                    # while it rings: the relay decides when the call is over.
                    user_left.clear()
                    c = await asyncio.to_thread(client.get_call, call_id)
                    if c["state"] == "active":
                        say("everyone left; waiting while the other phones still ring")
                    state = c["state"]
                    continue
                if stop.is_set():
                    c = await asyncio.to_thread(client.end_call, call_id)
                    state = c["state"]
                    break
                page = await asyncio.to_thread(client.list_events, after, 100)
                for e in page["events"]:
                    after = e.get("seq", after)
                    if e.get("call_id") != call_id:
                        continue
                    t = e["type"]
                    if t == "call.ringing":
                        say(f"ringing on {e['device_id']}")
                    elif t == "call.accept":
                        # Group calls: later phones join while the call is active.
                        verb = "joined" if state == "active" else "answered"
                        say(f"{verb} on {e.get('device_id', 'the phone')}")
                        state = "active"
                    elif t == "call.decline":
                        # Group calls: one phone declining doesn't end the call.
                        say(f"declined on {e.get('device_id', 'the phone')}")
                        state = (await asyncio.to_thread(client.get_call, call_id))["state"]
                    elif t == "call.missed":
                        say("no answer")
                        state = "missed"
                    elif t == "call.end":
                        say(f"ended by {e['by']} ({e['reason']})")
                        state = "ended"
                try:
                    await asyncio.wait_for(asyncio.shield(stop.wait()), 0.3)
                except asyncio.TimeoutError:
                    pass
        finally:
            if not joining.done():
                joining.cancel()
            await asyncio.gather(joining, return_exceptions=True)
            await worker.stop()
        return await asyncio.to_thread(client.get_call, call_id)

    try:
        final = asyncio.run(run())
    finally:
        client.close()
    if json_out:
        _emit_json(final)
    else:
        dur = f", {final['duration_ms'] / 1000:.0f} s" if final.get("duration_ms") is not None else ""
        typer.echo(f"Call {final['state']}{dur}")
    log.debug("call finished: %s", final)


@app.command()
def msg(
    text: Annotated[str, typer.Argument(help="Message text (max 4000 chars).")],
    reply_to: Annotated[Optional[str], typer.Option("--reply-to", help="msg_id or call_id this answers.")] = None,
    json_out: JsonOpt = False,
) -> None:
    """Send a message to the phone. Prints the msg_id."""
    s = _settings()
    with _client(s) as c:
        try:
            m = c.post_message(text, reply_to=reply_to)
        except RelayError as e:
            raise _fail(str(e)) from None
    store = Store(s.db_path)
    store.upsert_message(m["msg_id"], "agent", m["text"], m["created_at"], m.get("reply_to"))
    store.close()
    if json_out:
        _emit_json({"msg_id": m["msg_id"]})
    else:
        typer.echo(m["msg_id"])


@app.command()
def replies(
    since: Annotated[
        Optional[str], typer.Option("--since", help="Show all replies since: 10m, 2h, 1d, epoch ms or ISO date.")
    ] = None,
    all_: Annotated[bool, typer.Option("--all", help="Show all replies, not just new ones.")] = False,
    peek: Annotated[bool, typer.Option("--peek", help="Don't mark the replies as seen.")] = False,
    limit: Annotated[int, typer.Option("--limit", min=1, max=500)] = 100,
    json_out: JsonOpt = False,
) -> None:
    """Show the user's replies. By default only new ones (not shown before), which are then marked as seen."""
    s = _settings()
    store = Store(s.db_path)
    with _client(s) as c:
        try:
            catch_up(c, store)
        except RelayError as e:
            raise _fail(str(e)) from None

        since_ms = parse_since(since) if since else None
        undelivered_only = since is None and not all_
        rows = store.replies(since=since_ms, undelivered_only=undelivered_only, limit=limit)
        if rows and not peek:
            store.mark_delivered([r["msg_id"] for r in rows], _now_ms())
            for r in rows:
                if r["read_at"] is None:
                    try:
                        c.mark_read(r["msg_id"])
                    except RelayError:
                        pass  # best effort; the read receipt is cosmetic
    store.close()

    out = [
        {"msg_id": r["msg_id"], "text": r["text"], "created_at": r["created_at"], "reply_to": r["reply_to"]}
        for r in rows
    ]
    if json_out:
        _emit_json({"replies": out})
        return
    if not out:
        typer.echo("No new replies." if undelivered_only else "No replies.")
        return
    for r in out:
        typer.echo(f"[{_fmt_ts(r['created_at'])}] {r['text']}")


@app.command()
def status(json_out: JsonOpt = False) -> None:
    """Relay connection and paired devices."""
    s = _settings()
    with _client(s) as c:
        try:
            st = c.status()
            devs = c.list_devices()
        except RelayError as e:
            if json_out:
                _emit_json({"relay_url": s.relay_url, "reachable": False, "error": str(e)})
            raise _fail(str(e)) from None
    if json_out:
        _emit_json({"relay_url": s.relay_url, "reachable": True, **st, "devices": devs})
        return
    typer.echo(f"Relay:       {s.relay_url} ({st['relay_id']}, v{st['version']})")
    typer.echo(f"Agent name:  {st['agent_name']}")
    typer.echo(f"Daemon:      {'connected (serve running)' if st['agent_online'] else 'not connected'}")
    typer.echo(f"Push:        {st['push_provider']}")
    if not devs:
        typer.echo("Devices:     none — run `agentring pair`")
    for d in devs:
        seen = _fmt_ts(d["last_seen_at"]) if d.get("last_seen_at") else "never"
        push = "push ok" if d["has_push_token"] else "NO push token"
        typer.echo(f"Device:      {d['name']} ({d['device_id']}) — {push}, last seen {seen}")


@devices_app.callback(invoke_without_command=True)
def devices_list(
    ctx: typer.Context,
    include_revoked: Annotated[bool, typer.Option("--include-revoked")] = False,
    json_out: JsonOpt = False,
) -> None:
    """List paired devices."""
    if ctx.invoked_subcommand:
        return
    s = _settings()
    with _client(s) as c:
        try:
            devs = c.list_devices(include_revoked=include_revoked)
        except RelayError as e:
            raise _fail(str(e)) from None
    if json_out:
        _emit_json({"devices": devs})
        return
    if not devs:
        typer.echo("No devices paired.")
    for d in devs:
        state = f"revoked {_fmt_ts(d['revoked_at'])}" if d.get("revoked_at") else "active"
        typer.echo(f"{d['device_id']}  {d['name']:<24} paired {_fmt_ts(d['created_at'])}  {state}")


@devices_app.command("revoke")
def devices_revoke(device_id: Annotated[str, typer.Argument(help="Device id (dev_...).")]) -> None:
    """Revoke a device: its credentials stop working immediately."""
    s = _settings()
    with _client(s) as c:
        try:
            c.revoke_device(device_id)
        except RelayError as e:
            raise _fail(str(e)) from None
    typer.echo(f"Revoked {device_id}")


def _describe(event: dict[str, Any]) -> str | None:
    t = event.get("type")
    if t == "msg.new":
        who = "you" if event["from"] == "agent" else "user"
        return f"msg.new from {who}: {event['text']}"
    if t == "msg.read":
        return f"msg.read {event['msg_id']} by {event['by']}"
    if t == "ws.hello":
        return None
    return t


@app.command()
def serve() -> None:
    """Run the daemon: keeps a live connection to the relay (MCP, HTTP API and voice worker come in later milestones)."""
    s = _settings()
    store = Store(s.db_path)
    client = _client(s)
    log = logging.getLogger("agentring")

    def on_event(e: dict[str, Any]) -> None:
        d = _describe(e)
        if d:
            log.info(d)

    async def main() -> None:
        stop = asyncio.Event()
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            try:
                loop.add_signal_handler(sig, stop.set)
            except NotImplementedError:  # Windows
                pass
        log.info("agentring %s serving; relay %s", __version__, s.relay_url)
        await run_ws(client, s.api_key, store, on_event, stop=stop)

    try:
        asyncio.run(main())
    except FatalSyncError as e:
        raise _fail(str(e)) from None
    finally:
        client.close()
        store.close()
    log.info("stopped")


def main() -> None:
    app()


if __name__ == "__main__":
    main()
