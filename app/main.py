from __future__ import annotations

import html
import os
import re
from bisect import bisect_right
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path, PurePosixPath
from typing import Iterable

from fastapi import FastAPI
from fastapi.responses import FileResponse, JSONResponse
from plexapi.server import PlexServer


@dataclass(frozen=True)
class Settings:
    plex_url: str
    plex_token: str
    plex_client_filter: str
    plex_media_root: str
    container_media_root: str
    poll_interval_ms: int

    @classmethod
    def from_env(cls) -> "Settings":
        token = os.getenv("PLEX_TOKEN", "").strip()
        if not token:
            raise RuntimeError("PLEX_TOKEN is required")

        poll_ms = int(os.getenv("POLL_INTERVAL_MS", "750"))
        poll_ms = max(250, min(poll_ms, 5000))

        return cls(
            plex_url=os.getenv("PLEX_URL", "http://host.docker.internal:32400").rstrip("/"),
            plex_token=token,
            plex_client_filter=os.getenv("PLEX_CLIENT_FILTER", "").strip(),
            plex_media_root=os.getenv("PLEX_MEDIA_ROOT", "/media").strip(),
            container_media_root=os.getenv("CONTAINER_MEDIA_ROOT", "/media").strip(),
            poll_interval_ms=poll_ms,
        )


@dataclass(frozen=True)
class Cue:
    start: float
    end: float
    text: str


app = FastAPI(title="Plex Subtitles Companion", docs_url=None, redoc_url=None)
STATIC_DIR = Path(__file__).parent / "static"

SPANISH_SUFFIXES = (
    ".es.srt",
    ".es-es.srt",
    ".spa.srt",
    ".spanish.srt",
    ".español.srt",
    ".castellano.srt",
)

TAG_RE = re.compile(r"<[^>]+>")
ASS_OVERRIDE_RE = re.compile(r"\{\\[^}]*\}")


@lru_cache(maxsize=1)
def settings() -> Settings:
    return Settings.from_env()


@lru_cache(maxsize=1)
def plex_server() -> PlexServer:
    cfg = settings()
    return PlexServer(cfg.plex_url, cfg.plex_token, timeout=5)


def normalize_path(value: str) -> str:
    return value.replace("\\", "/").rstrip("/")


def map_media_path(plex_path: str) -> Path:
    cfg = settings()
    source = normalize_path(plex_path)
    source_root = normalize_path(cfg.plex_media_root)
    target_root = Path(cfg.container_media_root)

    source_cmp = source.casefold()
    root_cmp = source_root.casefold()

    if source_cmp == root_cmp:
        return target_root

    if source_cmp.startswith(root_cmp + "/"):
        relative = source[len(source_root):].lstrip("/")
        return target_root / PurePosixPath(relative)

    return Path(source)


def session_player_fields(session) -> tuple[str, str, str, str]:
    player = getattr(session, "player", None)
    if player is None:
        return "", "", "", "unknown"

    return (
        getattr(player, "title", "") or "",
        getattr(player, "product", "") or "",
        getattr(player, "device", "") or "",
        getattr(player, "state", "") or "unknown",
    )


def select_session(sessions: Iterable):
    items = list(sessions)
    if not items:
        return None

    client_filter = settings().plex_client_filter.casefold()
    if client_filter:
        for session in items:
            title, product, device, _ = session_player_fields(session)
            if client_filter in " ".join((title, product, device)).casefold():
                return session

    for session in items:
        if session_player_fields(session)[3] == "playing":
            return session

    return items[0]


def media_file_from_session(session) -> str | None:
    for media in getattr(session, "media", []) or []:
        for part in getattr(media, "parts", []) or []:
            file_path = getattr(part, "file", None)
            if file_path:
                return file_path

    rating_key = getattr(session, "ratingKey", None)
    if rating_key is None:
        return None

    item = plex_server().fetchItem(int(rating_key))
    for media in getattr(item, "media", []) or []:
        for part in getattr(media, "parts", []) or []:
            file_path = getattr(part, "file", None)
            if file_path:
                return file_path

    return None


def find_spanish_srt(media_file: Path) -> Path | None:
    folder = media_file.parent
    if not folder.is_dir():
        return None

    media_stem = media_file.stem.casefold()
    candidates = [
        path
        for path in folder.glob("*.srt")
        if path.name.casefold().startswith(media_stem)
    ]

    if not candidates:
        return None

    for suffix in SPANISH_SUFFIXES:
        for candidate in candidates:
            if candidate.name.casefold().endswith(suffix):
                return candidate

    return candidates[0] if len(candidates) == 1 else None


def parse_timestamp(value: str) -> float:
    hours, minutes, rest = value.strip().split(":")
    seconds, milliseconds = rest.replace(".", ",").split(",", 1)
    return (
        int(hours) * 3600
        + int(minutes) * 60
        + int(seconds)
        + int(milliseconds[:3].ljust(3, "0")) / 1000
    )


def clean_subtitle_text(value: str) -> str:
    value = ASS_OVERRIDE_RE.sub("", value)
    value = TAG_RE.sub("", value)
    return html.unescape(value).strip()


@lru_cache(maxsize=32)
def parse_srt_cached(path_string: str, mtime_ns: int) -> tuple[Cue, ...]:
    del mtime_ns
    text = Path(path_string).read_text(encoding="utf-8-sig", errors="replace")
    text = text.replace("\r\n", "\n").replace("\r", "\n")

    cues: list[Cue] = []
    for block in re.split(r"\n{2,}", text.strip()):
        lines = [line for line in block.splitlines() if line.strip()]
        timing_index = next((i for i, line in enumerate(lines) if "-->" in line), None)
        if timing_index is None:
            continue

        try:
            start_raw, end_raw = (part.strip() for part in lines[timing_index].split("-->", 1))
            end_raw = end_raw.split()[0]
            subtitle_text = clean_subtitle_text("\n".join(lines[timing_index + 1 :]))
            if subtitle_text:
                cues.append(Cue(parse_timestamp(start_raw), parse_timestamp(end_raw), subtitle_text))
        except (ValueError, IndexError):
            continue

    cues.sort(key=lambda cue: cue.start)
    return tuple(cues)


def load_cues(path: Path) -> tuple[Cue, ...]:
    stat = path.stat()
    return parse_srt_cached(str(path), stat.st_mtime_ns)


def cue_pair(cues: tuple[Cue, ...], position: float) -> tuple[Cue | None, Cue | None]:
    if not cues:
        return None, None

    starts = [cue.start for cue in cues]
    index = bisect_right(starts, position) - 1
    current = None

    if index >= 0:
        candidate = cues[index]
        if candidate.start <= position <= candidate.end:
            current = candidate
        elif 0 <= position - candidate.end <= 1.5:
            current = candidate

    next_index = max(index + 1, 0)
    next_cue = cues[next_index] if next_index < len(cues) else None
    return current, next_cue


def serialize_cue(cue: Cue | None) -> dict | None:
    if cue is None:
        return None
    return {"start": cue.start, "end": cue.end, "text": cue.text}


def display_title(session) -> str:
    title = getattr(session, "title", "") or ""
    show = getattr(session, "grandparentTitle", "") or ""
    season = getattr(session, "parentIndex", None)
    episode = getattr(session, "index", None)

    if show:
        episode_code = ""
        if season is not None and episode is not None:
            episode_code = f" · S{int(season):02d}E{int(episode):02d}"
        return f"{show}{episode_code} · {title}"

    return title or "Plex"


@app.get("/")
def index():
    return FileResponse(STATIC_DIR / "index.html")


@app.get("/api/health")
def health():
    return {"ok": True}


@app.get("/api/status")
def status():
    try:
        session = select_session(plex_server().sessions())

        if session is None:
            return {
                "ok": True,
                "active": False,
                "poll_interval_ms": settings().poll_interval_ms,
            }

        client, product, device, state = session_player_fields(session)
        position_ms = int(getattr(session, "viewOffset", 0) or 0)
        position = position_ms / 1000

        plex_media_path = media_file_from_session(session)
        media_path = map_media_path(plex_media_path) if plex_media_path else None
        subtitle_path = find_spanish_srt(media_path) if media_path else None

        current = next_cue = None
        if subtitle_path and subtitle_path.is_file():
            current, next_cue = cue_pair(load_cues(subtitle_path), position)

        return {
            "ok": True,
            "active": True,
            "title": display_title(session),
            "state": state,
            "client": client or product or device or "Plex",
            "position": position,
            "media_found": bool(media_path and media_path.is_file()),
            "subtitle_found": bool(subtitle_path and subtitle_path.is_file()),
            "current": serialize_cue(current),
            "next": serialize_cue(next_cue),
            "poll_interval_ms": settings().poll_interval_ms,
        }

    except Exception as exc:
        return JSONResponse(
            status_code=500,
            content={"ok": False, "error": f"{type(exc).__name__}: {exc}"},
        )
