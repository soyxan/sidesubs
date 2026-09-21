from __future__ import annotations

import logging
import threading
import time
from collections.abc import Callable

from app.domain import Cue
from app.subtitles import merge_cues, parse_timed_text


logger = logging.getLogger("sidesubs.subtitle_player")

StreamRunner = Callable[[float, threading.Event, Callable[[bytes], None]], None]


class SubtitlePlayer:
    """Provider-neutral subtitle timeline player.

    A provider supplies a blocking stream runner. SubtitlePlayer owns the
    background thread, accumulates timed-text payloads, parses cues, tracks
    playback position and restarts the stream only after a seek or stream end.
    """

    SEEK_THRESHOLD_SECONDS = 12.0
    START_PREROLL_SECONDS = 2.0

    def __init__(self, stream_runner: StreamRunner):
        self._stream_runner = stream_runner
        self._lock = threading.RLock()
        self._stop_event = threading.Event()
        self._thread: threading.Thread | None = None
        self._generation = 0
        self._cues: tuple[Cue, ...] = ()
        self._last_position: float | None = None
        self._last_sync_at: float | None = None
        self._last_state = "unknown"
        self._error: str | None = None
        self._started_at = 0.0
        self._timeline_offset = 0.0
        self._timeline_mode_logged = False
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
            # A transport session may end/recycle while the persistent player
            # already has cues buffered ahead of playback. Resume beyond the
            # last known cue instead of reopening from the current playback
            # position, otherwise PMS can keep returning the same cue until
            # playback catches up and SideSubs shows temporary gaps.
            with self._lock:
                resume_position = position
                has_buffer = bool(self._cues)
                if self._cues:
                    # Resume strictly beyond the last buffered cue. Do not
                    # apply normal playback preroll here, otherwise the PMS
                    # request falls back onto the cue that caused the recycle.
                    resume_position = max(position, self._cues[-1].end + 1.25)
            logger.info(
                "Subtitle transport resume playback=%.3f resume=%.3f buffered_last_end=%s preroll=%s",
                position,
                resume_position,
                f"{self._cues[-1].end:.3f}" if self._cues else "<none>",
                not has_buffer,
            )
            self.start(resume_position, preroll=not has_buffer)

        return self.cues()

    def start(self, position: float, *, preroll: bool = True) -> None:
        with self._lock:
            if self._thread is not None and self._thread.is_alive():
                return
            self._start_locked(position, preroll=preroll)

    def restart(self, position: float) -> None:
        self.stop(join_timeout=0.75)
        with self._lock:
            self._cues = ()
            self._error = None
            self._timeline_offset = 0.0
            self._timeline_mode_logged = False
            self._start_locked(position, preroll=True)

    def _start_locked(self, position: float, *, preroll: bool = True) -> None:
        self._generation += 1
        generation = self._generation
        self._stop_event = threading.Event()
        offset = self.START_PREROLL_SECONDS if preroll else 0.0
        self._started_at = max(0.0, position - offset)
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
            # Every PMS /subtitles response is a self-contained timed-text
            # document (typically ASS with its own headers). Parse it
            # independently, then merge its cues into the persistent timeline.
            text = payload.decode("utf-8", errors="replace")
            parsed = parse_timed_text(text)

            # PMS subtitle timestamps can be relative to the requested
            # transcode offset even when copyts=1. Detect that coordinate
            # system from the first useful cues and normalize everything to
            # the absolute Plex playback timeline.
            if parsed and not self._timeline_mode_logged:
                first = parsed[0]
                last = parsed[-1]
                if self._started_at >= 5.0 and last.end < self._started_at - 2.0:
                    self._timeline_offset = self._started_at
                    mode = "relative"
                else:
                    self._timeline_offset = 0.0
                    mode = "absolute"
                self._timeline_mode_logged = True
                logger.info(
                    "Subtitle timeline mode=%s requested_offset=%.3f raw_first=%.3f-%.3f raw_last=%.3f-%.3f cues=%s",
                    mode,
                    self._started_at,
                    first.start,
                    first.end,
                    last.start,
                    last.end,
                    len(parsed),
                )

            if self._timeline_offset:
                normalized = tuple(
                    Cue(
                        start=cue.start + self._timeline_offset,
                        end=cue.end + self._timeline_offset,
                        text=cue.text,
                    )
                    for cue in parsed
                )
            else:
                normalized = parsed

            self._cues = merge_cues(self._cues, normalized)

            if self._cues:
                logger.info(
                    "Subtitle buffer cues=%s first=%.3f-%.3f last=%.3f-%.3f",
                    len(self._cues),
                    self._cues[0].start,
                    self._cues[0].end,
                    self._cues[-1].start,
                    self._cues[-1].end,
                )
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
