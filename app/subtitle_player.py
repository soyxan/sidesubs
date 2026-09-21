from __future__ import annotations

import logging
import threading
import time
from collections.abc import Callable

from app.domain import Cue
from app.subtitles import parse_timed_text


logger = logging.getLogger("sidesubs.subtitle_player")

StreamRunner = Callable[[float, threading.Event, Callable[[bytes], None]], None]


class SubtitlePlayer:
    """Provider-neutral subtitle timeline player.

    A provider supplies a blocking stream runner. SubtitlePlayer owns the
    background thread, accumulates timed-text payloads, parses cues, tracks
    playback position and restarts the stream only after a seek or stream end.
    """

    SEEK_THRESHOLD_SECONDS = 3.0
    START_PREROLL_SECONDS = 2.0

    def __init__(self, stream_runner: StreamRunner):
        self._stream_runner = stream_runner
        self._lock = threading.RLock()
        self._stop_event = threading.Event()
        self._thread: threading.Thread | None = None
        self._generation = 0
        self._byte_buffer = b""
        self._cues: tuple[Cue, ...] = ()
        self._last_position: float | None = None
        self._last_sync_at: float | None = None
        self._last_state = "unknown"
        self._error: str | None = None
        self._started_at = 0.0
        self._last_used_at = time.monotonic()

    @property
    def last_used_at(self) -> float:
        with self._lock:
            return self._last_used_at

    def sync(self, position: float, state: str) -> tuple[Cue, ...]:
        now = time.monotonic()
        normalized_state = (state or "unknown").casefold()

        with self._lock:
            self._last_used_at = now
            needs_start = self._thread is None or not self._thread.is_alive()

            seek = False
            if self._last_position is not None and self._last_sync_at is not None:
                elapsed = max(0.0, now - self._last_sync_at)
                expected = self._last_position
                if self._last_state == "playing":
                    expected += elapsed
                seek = abs(position - expected) > self.SEEK_THRESHOLD_SECONDS

            self._last_position = position
            self._last_sync_at = now
            self._last_state = normalized_state

        if seek:
            logger.info("Subtitle player seek detected: position=%.3f", position)
            self.restart(position)
        elif needs_start:
            self.start(position)

        return self.cues()

    def start(self, position: float) -> None:
        with self._lock:
            if self._thread is not None and self._thread.is_alive():
                return
            self._start_locked(position)

    def restart(self, position: float) -> None:
        self.stop(join_timeout=0.75)
        with self._lock:
            self._byte_buffer = b""
            self._cues = ()
            self._error = None
            self._start_locked(position)

    def _start_locked(self, position: float) -> None:
        self._generation += 1
        generation = self._generation
        self._stop_event = threading.Event()
        self._started_at = max(0.0, position - self.START_PREROLL_SECONDS)
        thread = threading.Thread(
            target=self._run,
            args=(generation, self._started_at, self._stop_event),
            name=f"sidesubs-subtitle-player-{generation}",
            daemon=True,
        )
        self._thread = thread
        thread.start()

    def stop(self, join_timeout: float = 1.0) -> None:
        with self._lock:
            thread = self._thread
            self._stop_event.set()
            self._thread = None
        if thread is not None and thread.is_alive():
            thread.join(timeout=join_timeout)

    def cues(self) -> tuple[Cue, ...]:
        with self._lock:
            return self._cues

    def error(self) -> str | None:
        with self._lock:
            return self._error

    def _on_payload(self, generation: int, payload: bytes) -> None:
        if not payload:
            return
        with self._lock:
            if generation != self._generation:
                return
            self._byte_buffer += payload
            # Decode the complete accumulated byte stream so an HTTP chunk
            # boundary cannot corrupt a multi-byte UTF-8 character.
            text = self._byte_buffer.decode("utf-8", errors="replace")
            # Subtitle streams are tiny compared with media. Keeping the
            # accumulated text lets ASS fragments without repeated headers be
            # parsed correctly and still remains bounded for normal titles.
            self._cues = parse_timed_text(text)
            self._error = None

    def _run(self, generation: int, start_position: float, stop_event: threading.Event) -> None:
        try:
            self._stream_runner(
                start_position,
                stop_event,
                lambda payload: self._on_payload(generation, payload),
            )
        except Exception as exc:
            if not stop_event.is_set():
                logger.warning("Subtitle stream ended with error: %s", exc)
                with self._lock:
                    if generation == self._generation:
                        self._error = "Subtitle stream temporarily unavailable"
        finally:
            with self._lock:
                if generation == self._generation and self._thread is threading.current_thread():
                    self._thread = None
