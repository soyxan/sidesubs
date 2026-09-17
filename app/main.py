from __future__ import annotations

import html
import json
import logging
import os
import re
import subprocess
from bisect import bisect_right
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path, PurePosixPath
from typing import Iterable

from fastapi import FastAPI
from fastapi.responses import FileResponse, JSONResponse
from plexapi.server import PlexServer


logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
logger = logging.getLogger("plex-subtitles")


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
SPANISH_LANGS = {"es", "spa", "esp", "es-es", "es_419"}
SPANISH_TITLE_WORDS = ("spanish", "español", "espanol", "castellano")
TEXT_SUBTITLE_CODECS = {"subrip", "srt", "ass", "ssa", "webvtt", "mov_text"}

TAG_RE = re.compile(r"<[^>]+>")
ASS_OVERRIDE_RE = re.compile(r"\{\\[^}]*\}")


@lru_cache(maxsize=1)
def settings() -> Settings:
    cfg = Settings.from_env()
    logger.info(
        "Configuration loaded: plex_url=%s client_filter=%s plex_media_root=%s container_media_root=%s poll_ms=%s",
        cfg.plex_url,
        cfg.plex_client_filter or "<none>",
        cfg.plex_media_root,
        cfg.container_media_root,
        cfg.poll_interval_ms,
    )
    return cfg


@lru_cache(maxsize=1)
def plex_server() -> PlexServer:
    cfg = settings()
    logger.info("Connecting to Plex at %s", cfg.plex_url)
    server = PlexServer(cfg.plex_url, cfg.plex_token, timeout=5)
    logger.info("Connected to Plex server: %s", getattr(server, "friendlyName", "unknown"))
    return server


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


def subtitle_candidates(media_file: Path) -> list[Path]:
    folder = media_file.parent
    if not folder.is_dir():
        return []

    media_stem = media_file.stem.casefold()
    return [
        path
        for path in folder.iterdir()
        if path.is_file()
        and path.suffix.casefold() == ".srt"
        and path.name.casefold().startswith(media_stem)
    ]


def find_spanish_srt(media_file: Path) -> Path | None:
    candidates = subtitle_candidates(media_file)
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


def parse_srt_text(text: str) -> tuple[Cue, ...]:
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


@lru_cache(maxsize=32)
def parse_srt_cached(path_string: str, mtime_ns: int) -> tuple[Cue, ...]:
    del mtime_ns
    text = Path(path_string).read_text(encoding="utf-8-sig", errors="replace")
    cues = parse_srt_text(text)
    logger.info("Loaded %s subtitle cues from external SRT %s", len(cues), path_string)
    return cues


def load_cues(path: Path) -> tuple[Cue, ...]:
    stat = path.stat()
    return parse_srt_cached(str(path), stat.st_mtime_ns)


@lru_cache(maxsize=32)
def probe_subtitle_streams_cached(path_string: str, mtime_ns: int) -> tuple[dict, ...]:
    del mtime_ns
    command = [
        "ffprobe",
        "-v",
        "error",
        "-select_streams",
        "s",
        "-show_entries",
        "stream=index,codec_name:stream_tags=language,title",
        "-of",
        "json",
        path_string,
    ]
    completed = subprocess.run(command, capture_output=True, text=True, timeout=20, check=True)
    payload = json.loads(completed.stdout or "{}")
    streams = tuple(payload.get("streams", []) or [])
    logger.info("Found %s embedded subtitle streams in %s", len(streams), path_string)
    return streams


def probe_subtitle_streams(media_file: Path) -> tuple[dict, ...]:
    stat = media_file.stat()
    return probe_subtitle_streams_cached(str(media_file), stat.st_mtime_ns)


def subtitle_stream_summary(stream: dict) -> dict:
    tags = stream.get("tags") or {}
    return {
        "index": stream.get("index"),
        "codec": stream.get("codec_name"),
        "language": tags.get("language"),
        "title": tags.get("title"),
    }


def spanish_stream_score(stream: dict) -> int:
    tags = stream.get("tags") or {}
    language = str(tags.get("language") or "").casefold()
    title = str(tags.get("title") or "").casefold()
    codec = str(stream.get("codec_name") or "").casefold()

    score = 0
    if language in SPANISH_LANGS:
        score += 100
    if any(word in title for word in SPANISH_TITLE_WORDS):
        score += 50
    if codec in TEXT_SUBTITLE_CODECS:
        score += 10
    return score


def select_spanish_embedded_stream(media_file: Path) -> dict | None:
    streams = list(probe_subtitle_streams(media_file))
    if not streams:
        return None

    ranked = sorted(streams, key=spanish_stream_score, reverse=True)
    best = ranked[0]
    return best if spanish_stream_score(best) >= 50 else None


@lru_cache(maxsize=32)
def extract_embedded_cues_cached(path_string: str, mtime_ns: int, stream_index: int) -> tuple[Cue, ...]:
    del mtime_ns
    command = [
        "ffmpeg",
        "-v",
        "error",
        "-i",
        path_string,
        "-map",
        f"0:{stream_index}",
        "-f",
        "srt",
        "-",
    ]
    completed = subprocess.run(command, capture_output=True, text=True, timeout=60)
    if completed.returncode != 0:
        error = (completed.stderr or "").strip()
        raise RuntimeError(f"Unable to convert embedded subtitle stream {stream_index} to SRT: {error}")

    cues = parse_srt_text(completed.stdout)
    logger.info(
        "Loaded %s subtitle cues from embedded stream %s in %s",
        len(cues),
        stream_index,
        path_string,
    )
    return cues


def load_embedded_cues(media_file: Path, stream: dict) -> tuple[Cue, ...]:
    stream_index = int(stream["index"])
    stat = media_file.stat()
    return extract_embedded_cues_cached(str(media_file), stat.st_mtime_ns, stream_index)


def resolve_spanish_subtitles(media_file: Path) -> tuple[tuple[Cue, ...], dict]:
    external = find_spanish_srt(media_file)
    if external and external.is_file():
        return load_cues(external), {
            "source": "external_srt",
            "path": str(external),
            "stream": None,
        }

    stream = select_spanish_embedded_stream(media_file)
    if stream is None:
        return (), {"source": None, "path": None, "stream": None}

    codec = str(stream.get("codec_name") or "").casefold()
    metadata = {
        "source": "embedded",
        "path": str(media_file),
        "stream": subtitle_stream_summary(stream),
    }

    if codec not in TEXT_SUBTITLE_CODECS:
        metadata["error"] = (
            f"Embedded Spanish subtitle codec '{codec}' is image-based or unsupported; "
            "it cannot be converted to text automatically."
        )
        return (), metadata

    try:
        return load_embedded_cues(media_file, stream), metadata
    except Exception as exc:
        metadata["error"] = str(exc)
        logger.warning("Embedded subtitle extraction failed: %s", exc)
        return (), metadata


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


def serialize_session(session) -> dict:
    client, product, device, state = session_player_fields(session)
    return {
        "client": client or None,
        "product": product or None,
        "device": device or None,
        "state": state,
        "title": display_title(session),
        "position": int(getattr(session, "viewOffset", 0) or 0) / 1000,
        "rating_key": str(getattr(session, "ratingKey", "") or "") or None,
    }


def build_diagnostics() -> dict:
    cfg = settings()
    result = {
        "ok": True,
        "plex_url": cfg.plex_url,
        "plex_connected": False,
        "plex_server": None,
        "session_found": False,
        "session_count": 0,
        "sessions": [],
        "selected_client": None,
        "state": None,
        "position": None,
        "plex_media_path": None,
        "container_media_path": None,
        "media_exists": False,
        "subtitle_candidates": [],
        "embedded_subtitles": [],
        "selected_subtitle_source": None,
        "selected_subtitle_stream": None,
        "subtitle_path": None,
        "subtitle_found": False,
        "subtitle_error": None,
    }

    plex = plex_server()
    result["plex_connected"] = True
    result["plex_server"] = getattr(plex, "friendlyName", None)

    sessions = list(plex.sessions())
    result["session_count"] = len(sessions)
    result["sessions"] = [serialize_session(item) for item in sessions]
    session = select_session(sessions)
    if session is None:
        return result

    result["session_found"] = True
    client, product, device, state = session_player_fields(session)
    result["selected_client"] = client or product or device or "Plex"
    result["state"] = state
    result["position"] = int(getattr(session, "viewOffset", 0) or 0) / 1000

    plex_media_path = media_file_from_session(session)
    result["plex_media_path"] = plex_media_path
    if not plex_media_path:
        return result

    media_path = map_media_path(plex_media_path)
    result["container_media_path"] = str(media_path)
    result["media_exists"] = media_path.is_file()
    if not media_path.is_file():
        return result

    candidates = subtitle_candidates(media_path)
    result["subtitle_candidates"] = [str(path) for path in candidates]

    try:
        embedded = probe_subtitle_streams(media_path)
        result["embedded_subtitles"] = [subtitle_stream_summary(stream) for stream in embedded]
    except Exception as exc:
        result["subtitle_error"] = f"ffprobe failed: {exc}"
        return result

    cues, metadata = resolve_spanish_subtitles(media_path)
    result["selected_subtitle_source"] = metadata.get("source")
    result["selected_subtitle_stream"] = metadata.get("stream")
    result["subtitle_path"] = metadata.get("path")
    result["subtitle_error"] = metadata.get("error")
    result["subtitle_found"] = bool(cues)
    return result


@app.get("/")
def index():
    return FileResponse(STATIC_DIR / "index.html")


@app.get("/api/health")
def health():
    return {"ok": True}


@app.get("/api/debug")
def debug():
    try:
        return build_diagnostics()
    except Exception as exc:
        logger.exception("Diagnostics failed")
        return JSONResponse(
            status_code=500,
            content={"ok": False, "error": f"{type(exc).__name__}: {exc}"},
        )


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

        current = next_cue = None
        subtitle_found = False
        subtitle_source = None
        subtitle_error = None

        if media_path and media_path.is_file():
            cues, metadata = resolve_spanish_subtitles(media_path)
            subtitle_source = metadata.get("source")
            subtitle_error = metadata.get("error")
            subtitle_found = bool(cues)
            if cues:
                current, next_cue = cue_pair(cues, position)

        logger.debug(
            "Session client=%s state=%s position=%.3fs plex_media=%s mapped_media=%s subtitle_source=%s subtitle_error=%s",
            client or product or device or "Plex",
            state,
            position,
            plex_media_path,
            media_path,
            subtitle_source,
            subtitle_error,
        )

        return {
            "ok": True,
            "active": True,
            "title": display_title(session),
            "state": state,
            "client": client or product or device or "Plex",
            "position": position,
            "media_found": bool(media_path and media_path.is_file()),
            "subtitle_found": subtitle_found,
            "subtitle_source": subtitle_source,
            "subtitle_error": subtitle_error,
            "current": serialize_cue(current),
            "next": serialize_cue(next_cue),
            "poll_interval_ms": settings().poll_interval_ms,
        }

    except Exception as exc:
        logger.exception("Status update failed")
        return JSONResponse(
            status_code=500,
            content={"ok": False, "error": f"{type(exc).__name__}: {exc}"},
        )
