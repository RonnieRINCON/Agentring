"""M2 voice worker: joins the call's LiveKit room, greets, then echoes the user back.

Good enough to prove that ringing, answering and two-way audio work end to end.
The real conversation (Gemini Live / OpenAI Realtime) replaces it in M3.
"""

from __future__ import annotations

import asyncio
import logging
import math
import shutil
import struct
import subprocess
import tempfile
import wave
from collections import deque
from pathlib import Path

from livekit import rtc

log = logging.getLogger("agentring.voice")

SAMPLE_RATE = 48_000
FRAME_MS = 10
SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MS // 1000
ECHO_DELAY_MS = 600


def _frame(samples: bytes) -> rtc.AudioFrame:
    return rtc.AudioFrame(samples, SAMPLE_RATE, 1, len(samples) // 2)


def _silence() -> bytes:
    return b"\x00\x00" * SAMPLES_PER_FRAME


def _beeps() -> bytes:
    """Two short 660 Hz beeps, as a fallback greeting."""
    out = bytearray()
    for on_ms, off_ms in ((180, 120), (180, 200)):
        n = SAMPLE_RATE * on_ms // 1000
        for i in range(n):
            env = min(1.0, i / 480, (n - i) / 480)  # 10 ms fade in/out, no clicks
            out += struct.pack("<h", int(9000 * env * math.sin(2 * math.pi * 660 * i / SAMPLE_RATE)))
        out += b"\x00\x00" * (SAMPLE_RATE * off_ms // 1000)
    return bytes(out)


def synthesize(text: str, voice: str | None = None) -> bytes:
    """48 kHz mono PCM16 for `text` using macOS `say`; beeps elsewhere or on failure."""
    if text and shutil.which("say"):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d) / "greeting.wav"
            # The text goes through a file, never argv: a text starting with "-" would be read as an option.
            src = Path(d) / "greeting.txt"
            src.write_text(text, encoding="utf-8")
            try:
                subprocess.run(
                    ["say", *(["-v", voice] if voice else []), "--file-format=WAVE",
                     f"--data-format=LEI16@{SAMPLE_RATE}", "-o", str(out), "-f", str(src)],
                    check=True,
                    timeout=20,
                    capture_output=True,
                )
                with wave.open(str(out)) as w:
                    if w.getnchannels() == 1 and w.getsampwidth() == 2 and w.getframerate() == SAMPLE_RATE:
                        return w.readframes(w.getnframes())
            except (subprocess.SubprocessError, OSError, wave.Error) as e:
                log.debug("say failed: %s", e)
    return _beeps()


class EchoWorker:
    """Publishes one audio track; plays the greeting once the user joins, then echoes them."""

    def __init__(self, greeting_pcm: bytes, on_user_left: asyncio.Event):
        self.room = rtc.Room()
        self.source = rtc.AudioSource(SAMPLE_RATE, 1, queue_size_ms=200)
        self.greeting = greeting_pcm
        self.user_left = on_user_left
        self.user_joined = asyncio.Event()
        self._greeted = False
        self._tasks: list[asyncio.Task] = []
        self._streams: list[rtc.AudioStream] = []

        @self.room.on("participant_connected")
        def _joined(p: rtc.RemoteParticipant) -> None:
            log.info("user joined the room (%s)", p.identity)
            self.user_joined.set()

        @self.room.on("participant_disconnected")
        def _left(p: rtc.RemoteParticipant) -> None:
            log.info("user left the room (%s)", p.identity)
            self.user_left.set()

        @self.room.on("track_subscribed")
        def _track(track: rtc.Track, _pub: rtc.RemoteTrackPublication, p: rtc.RemoteParticipant) -> None:
            if track.kind == rtc.TrackKind.KIND_AUDIO:
                log.info("receiving audio from %s", p.identity)
                self._tasks.append(asyncio.create_task(self._echo(track)))

        @self.room.on("disconnected")
        def _disconnected(reason: object) -> None:
            log.info("left the room (%s)", reason)
            self.user_left.set()

    async def start(self, url: str, token: str) -> None:
        await self.room.connect(url, token, rtc.RoomOptions(auto_subscribe=True))
        track = rtc.LocalAudioTrack.create_audio_track("agent", self.source)
        await self.room.local_participant.publish_track(
            track, rtc.TrackPublishOptions(source=rtc.TrackSource.SOURCE_MICROPHONE)
        )
        if self.room.remote_participants:
            self.user_joined.set()
        self._tasks.append(asyncio.create_task(self._greet()))

    async def _greet(self) -> None:
        await self.user_joined.wait()
        await asyncio.sleep(0.4)  # let the phone's audio pipeline settle so the first word isn't clipped
        step = SAMPLES_PER_FRAME * 2
        for i in range(0, len(self.greeting), step):
            chunk = self.greeting[i : i + step].ljust(step, b"\x00")
            await self.source.capture_frame(_frame(chunk))
        self._greeted = True

    async def _echo(self, track: rtc.Track) -> None:
        stream = rtc.AudioStream(track, sample_rate=SAMPLE_RATE, num_channels=1)
        self._streams.append(stream)
        delay = deque([_silence()] * (ECHO_DELAY_MS // FRAME_MS))
        pending = b""
        step = SAMPLES_PER_FRAME * 2
        async for ev in stream:
            pending += bytes(ev.frame.data.cast("B"))
            while len(pending) >= step:
                delay.append(pending[:step])
                pending = pending[step:]
                out = delay.popleft()
                # Don't talk over the greeting.
                if self._greeted:
                    await self.source.capture_frame(_frame(out))

    async def stop(self) -> None:
        for t in self._tasks:
            t.cancel()
        await asyncio.gather(*self._tasks, return_exceptions=True)
        for s in self._streams:
            await s.aclose()
        await self.source.aclose()
        await self.room.disconnect()
