from __future__ import annotations

import json

import httpx
import pytest
import typer

from agentring.cli import parse_since
from agentring.relay_client import RelayClient
from agentring.store import Store
from agentring.sync import apply_event

NOW = 1_761_200_000_000


def test_parse_since():
    assert parse_since("10m", NOW) == NOW - 600_000
    assert parse_since("2h", NOW) == NOW - 7_200_000
    assert parse_since("1d", NOW) == NOW - 86_400_000
    assert parse_since("1761200000000", NOW) == 1_761_200_000_000
    assert parse_since("2025-10-23T06:13:20Z", NOW) == 1_761_200_000_000
    with pytest.raises(typer.BadParameter):
        parse_since("yesterday", NOW)


def test_apply_event_is_idempotent_and_tracks_seq():
    s = Store(":memory:")
    ev = {"type": "msg.new", "id": "evt_1", "ts": NOW, "seq": 3, "msg_id": "msg_A", "from": "user", "text": "hi"}
    apply_event(s, ev)
    apply_event(s, ev)
    assert [r["text"] for r in s.replies()] == ["hi"]
    assert s.last_seq == 3
    apply_event(s, {"type": "msg.read", "id": "evt_2", "ts": NOW + 1, "seq": 4, "msg_id": "msg_A", "by": "agent"})
    assert s.replies()[0]["read_at"] == NOW + 1
    apply_event(s, {"type": "agent.presence", "id": "evt_3", "ts": NOW, "online": True})
    assert s.last_seq == 4


def test_relay_change_resets_cursor():
    s = Store(":memory:")
    assert s.reset_if_relay_changed("rly_A") is False
    s.advance_seq(10)
    assert s.reset_if_relay_changed("rly_A") is False
    assert s.last_seq == 10
    assert s.reset_if_relay_changed("rly_B") is True
    assert s.last_seq == 0


def test_undelivered_replies():
    s = Store(":memory:")
    s.upsert_message("msg_1", "user", "a", NOW)
    s.upsert_message("msg_2", "agent", "b", NOW)
    s.upsert_message("msg_3", "user", "c", NOW + 5)
    assert [r["text"] for r in s.replies(undelivered_only=True)] == ["a", "c"]
    s.mark_delivered(["msg_1"], NOW)
    assert [r["text"] for r in s.replies(undelivered_only=True)] == ["c"]
    assert [r["text"] for r in s.replies(since=NOW + 1)] == ["c"]


def test_create_call_sends_device_ids_only_when_given():
    bodies: list[dict] = []

    def handler(req: httpx.Request) -> httpx.Response:
        bodies.append(json.loads(req.content))
        return httpx.Response(201, json={"call": {}, "join": {}})

    with RelayClient("http://relay.test", "ak_x", transport=httpx.MockTransport(handler)) as c:
        c.create_call("all")
        c.create_call("one", device_ids=["dev_A"])
    assert bodies == [
        {"reason": "all", "priority": "normal"},
        {"reason": "one", "priority": "normal", "device_ids": ["dev_A"]},
    ]
