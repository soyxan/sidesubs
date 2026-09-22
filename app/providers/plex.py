from __future__ import annotations

import logging
import threading
import time
import uuid

import requests
from plexapi.server import PlexServer

from app.domain import Cue, PlaybackSession, SubtitleTrack
from app.providers.base import MediaProvider
from app.subtitles import infer_language, parse_timed_text


logger = logging.getLogger("sidesubs.provider.plex")

TEXT_SUBTITLE_CODECS = {"subrip", "srt", "ass", "ssa", "webvtt", "vtt", "mov_text", "text"}


class PlexProvider(MediaProvider):
    name = "plex"

    def __init__(self, base_url: str, token: str):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self._server: PlexServer | None = None
        self._lock = threading.RLock()
        self._transcode_lock = threading.Lock()
        self._track_cache: dict[str, tuple[float, list[SubtitleTrack]]] = {}
        self._subtitle_cache: dict[tuple[str, str], tuple[Cue, ...]] = {}
        self._subtitle_cache_info: dict[tuple[str, str], dict] = {}
        self._track_cache_ttl = 30.0
        self._subtitle_cache_max_entries = 32

    def _plex(self) -> PlexServer:
        # Multiple API requests can arrive concurrently while SideSubs is
        # starting. Serialize PlexServer creation so only one connection
        # attempt is in flight and the others reuse the same instance.
        with self._lock:
            if self._server is None:
                logger.info("Connecting to Plex at %s", self.base_url)
                self._server = PlexServer(self.base_url, self.token, timeout=5)
                logger.info("Connected to Plex server: %s", getattr(self._server, "friendlyName", "unknown"))
            return self._server

    @staticmethod
    def _player_id(native) -> str:
        player = getattr(native, "player", None)
        machine_id = getattr(player, "machineIdentifier", None)
        if machine_id:
            return str(machine_id)
        title = getattr(player, "title", None) or ""
        product = getattr(player, "product", None) or ""
        device = getattr(player, "device", None) or ""
        return f"fallback:{title}|{product}|{device}"

    @staticmethod
    def _display_title(native) -> str:
        title = getattr(native, "title", "") or ""
        show = getattr(native, "grandparentTitle", "") or ""
        season = getattr(native, "parentIndex", None)
        episode = getattr(native, "index", None)
        if show:
            episode_code = ""
            if season is not None and episode is not None:
                episode_code = f" · S{int(season):02d}E{int(episode):02d}"
            return f"{show}{episode_code} · {title}"
        return title or "Plex"

    def _to_session(self, native) -> PlaybackSession:
        player = getattr(native, "player", None)
        client = getattr(player, "title", "") or ""
        product = getattr(player, "product", "") or ""
        device = getattr(player, "device", "") or ""
        state = getattr(player, "state", "") or "unknown"
        rating_key = str(getattr(native, "ratingKey", "") or "")
        session_key = str(getattr(native, "sessionKey", "") or "")
        player_id = self._player_id(native)
        return PlaybackSession(
            id=f"{player_id}:{rating_key}:{session_key}",
            player_id=player_id,
            client=client,
            product=product,
            device=device,
            state=state,
            title=self._display_title(native),
            position=int(getattr(native, "viewOffset", 0) or 0) / 1000.0,
            rating_key=rating_key,
            native=native,
        )

    def list_sessions(self) -> list[PlaybackSession]:
        return [self._to_session(item) for item in self._plex().sessions()]

    def _item_part(self, session: PlaybackSession):
        item = self._plex().fetchItem(int(session.rating_key))
        media = list(getattr(item, "media", []) or [])
        if not media:
            raise RuntimeError("Plex media item has no media")
        parts = list(getattr(media[0], "parts", []) or [])
        if not parts:
            raise RuntimeError("Plex media item has no parts")
        return item, parts[0]

    def list_subtitle_tracks(self, session: PlaybackSession) -> list[SubtitleTrack]:
        now = time.monotonic()
        with self._lock:
            cached = self._track_cache.get(session.rating_key)
            if cached and now - cached[0] <= self._track_cache_ttl:
                return cached[1]

        _, part = self._item_part(session)
        tracks: list[SubtitleTrack] = []
        for stream in part.subtitleStreams():
            codec = str(getattr(stream, "codec", "") or "").casefold()
            stream_id = str(getattr(stream, "id", "") or "")
            stream_index = getattr(stream, "index", None)
            title = getattr(stream, "title", None) or getattr(stream, "extendedDisplayTitle", None)
            language = (
                infer_language(getattr(stream, "languageCode", None))
                or infer_language(getattr(stream, "language", None))
                or infer_language(title)
            )
            key = getattr(stream, "key", None)
            source = "external" if key else "embedded"
            display_title = title or getattr(stream, "displayTitle", None) or (
                f"{language.upper()}" if language else f"Subtitle {stream_index if stream_index is not None else stream_id}"
            )
            tracks.append(
                SubtitleTrack(
                    id=f"plex:{stream_id}",
                    source=source,
                    language=language or None,
                    title=str(display_title),
                    codec=codec or None,
                    compatible=codec in TEXT_SUBTITLE_CODECS,
                    provider_data={
                        "part_id": int(getattr(part, "id")),
                        "stream_id": int(stream_id),
                        "stream_index": int(stream_index) if stream_index is not None else None,
                        "key": key,
                    },
                )
            )

        with self._lock:
            self._track_cache[session.rating_key] = (now, tracks)
        return tracks

    def _request_headers(self, playback_session_id: str) -> dict[str, str]:
        return {
            "X-Plex-Token": self.token,
            "X-Plex-Client-Identifier": f"sidesubs-{uuid.uuid4()}",
            "X-Plex-Product": "SideSubs",
            "X-Plex-Version": "1",
            "X-Plex-Platform": "Chrome",
            "X-Plex-Device": "SideSubs",
            "X-Plex-Client-Profile-Name": "Web",
            "X-Plex-Session-Identifier": playback_session_id,
            "Accept": "*/*",
        }

    @staticmethod
    def _selected_stream_id(part) -> int:
        selected = [stream for stream in part.subtitleStreams() if getattr(stream, "selected", False)]
        return int(getattr(selected[0], "id")) if selected else 0

    def _cache_subtitle(
        self,
        cache_key: tuple[str, str],
        cues: tuple[Cue, ...],
        info: dict,
    ) -> tuple[Cue, ...]:
        with self._lock:
            self._subtitle_cache[cache_key] = cues
            self._subtitle_cache_info[cache_key] = info
            while len(self._subtitle_cache) > self._subtitle_cache_max_entries:
                oldest = next(iter(self._subtitle_cache))
                self._subtitle_cache.pop(oldest, None)
                self._subtitle_cache_info.pop(oldest, None)
        return cues

    def _fetch_external(self, session: PlaybackSession, track: SubtitleTrack) -> tuple[Cue, ...]:
        cache_key = (session.rating_key, track.id)
        with self._lock:
            cached = self._subtitle_cache.get(cache_key)
            if cached is not None:
                return cached

        key = track.provider_data.get("key")
        if not key:
            return ()

        response = requests.get(
            f"{self.base_url}{key}",
            params={"X-Plex-Token": self.token, "encoding": "utf-8", "format": "srt"},
            timeout=10,
        )
        response.raise_for_status()
        cues = parse_timed_text(response.text)
        if not cues:
            raise RuntimeError("Plex returned an external subtitle with no parseable cues")

        logger.info(
            "Loaded complete external subtitle rating_key=%s track=%s cues=%s first=%.3f last=%.3f",
            session.rating_key,
            track.id,
            len(cues),
            cues[0].start,
            cues[-1].end,
        )
        return self._cache_subtitle(
            cache_key,
            cues,
            {
                "mode": "external",
                "bytes": len(response.content),
                "content_type": response.headers.get("content-type"),
            },
        )

    def _fetch_embedded_full(self, session: PlaybackSession, track: SubtitleTrack) -> tuple[Cue, ...]:
        cache_key = (session.rating_key, track.id)
        with self._lock:
            cached = self._subtitle_cache.get(cache_key)
            if cached is not None:
                return cached

        part_id = int(track.provider_data["part_id"])
        stream_id = int(track.provider_data["stream_id"])

        # Selecting a subtitle stream modifies Plex Part state globally, so the
        # complete-file extraction is serialized. The selected stream is
        # restored immediately after the HTTP subtitle transcode finishes.
        with self._transcode_lock:
            with self._lock:
                cached = self._subtitle_cache.get(cache_key)
                if cached is not None:
                    return cached

            transcode_session = uuid.uuid4().hex[:24]
            playback_session_id = uuid.uuid4().hex
            headers = self._request_headers(playback_session_id)
            headers["Accept"] = "application/json"

            common_params = {
                "hasMDE": 1,
                "path": f"/library/metadata/{session.rating_key}",
                "mediaIndex": 0,
                "partIndex": 0,
                "fastSeek": 1,
                "directPlay": 1,
                "directStream": 1,
                "directStreamAudio": 1,
                "subtitleSize": 100,
                "audioBoost": 100,
                "videoQuality": 100,
                "videoResolution": "4096x2160",
                "location": "lan",
                "mediaBufferSize": 50000,
                "session": transcode_session,
                "subtitles": "sidecar",
                "subtitleStreamID": stream_id,
                "X-Plex-Token": self.token,
            }
            decision_params = {**common_params, "protocol": "hls"}
            subtitle_params = {
                **common_params,
                "protocol": "http",
                "copyts": 1,
                "offset": 0,
            }

            item = self._plex().fetchItem(int(session.rating_key))
            media = list(getattr(item, "media", []) or [])
            if not media or not getattr(media[0], "parts", None):
                raise RuntimeError("Plex media item has no parts")
            part = media[0].parts[0]
            old_stream_id = self._selected_stream_id(part)
            changed_global_selection = old_stream_id != stream_id

            if changed_global_selection:
                select_response = requests.put(
                    f"{self.base_url}/library/parts/{part_id}",
                    params={
                        "allParts": 1,
                        "subtitleStreamID": stream_id,
                        "X-Plex-Token": self.token,
                    },
                    timeout=5,
                )
                select_response.raise_for_status()

            response = None
            try:
                decision = requests.get(
                    f"{self.base_url}/video/:/transcode/universal/decision",
                    params=decision_params,
                    headers=headers,
                    timeout=10,
                )
                decision.raise_for_status()

                response = requests.get(
                    f"{self.base_url}/subtitles/:/transcode/universal/start",
                    params=subtitle_params,
                    headers=headers,
                    timeout=(5, 120),
                )
                if response.status_code >= 400:
                    body = response.text[:1000]
                    raise RuntimeError(
                        f"Plex full-subtitle request returned HTTP {response.status_code}: {body}"
                    )
                payload = response.content
            finally:
                if changed_global_selection:
                    try:
                        restore_response = requests.put(
                            f"{self.base_url}/library/parts/{part_id}",
                            params={
                                "allParts": 1,
                                "subtitleStreamID": old_stream_id,
                                "X-Plex-Token": self.token,
                            },
                            timeout=5,
                        )
                        restore_response.raise_for_status()
                    except Exception:
                        logger.exception("Unable to restore Plex subtitle stream %s", old_stream_id)

            text = payload.decode("utf-8", errors="replace")
            cues = parse_timed_text(text)
            if not cues:
                raise RuntimeError("Plex returned a complete subtitle response with no parseable cues")

            duration = float(getattr(session.native, "duration", 0) or 0) / 1000.0
            coverage = (cues[-1].end / duration) if duration else None
            logger.info(
                "Loaded complete embedded subtitle rating_key=%s stream=%s bytes=%s cues=%s first=%.3f last=%.3f coverage=%s",
                session.rating_key,
                stream_id,
                len(payload),
                len(cues),
                cues[0].start,
                cues[-1].end,
                f"{coverage:.4f}" if coverage is not None else "<unknown>",
            )
            return self._cache_subtitle(
                cache_key,
                cues,
                {
                    "mode": "plex_http_transcode",
                    "bytes": len(payload),
                    "content_type": response.headers.get("content-type") if response is not None else None,
                },
            )

    def _load_subtitle_cues(
        self,
        session: PlaybackSession,
        track: SubtitleTrack,
    ) -> tuple[Cue, ...]:
        if not track.compatible:
            return ()
        if track.source == "external" and track.provider_data.get("key"):
            return self._fetch_external(session, track)
        return self._fetch_embedded_full(session, track)

    def debug_fetch_full_subtitle(self, session: PlaybackSession, track: SubtitleTrack) -> dict:
        cues = self._load_subtitle_cues(session, track)
        cache_key = (session.rating_key, track.id)
        with self._lock:
            info = dict(self._subtitle_cache_info.get(cache_key, {}))

        duration = float(getattr(session.native, "duration", 0) or 0) / 1000.0
        return {
            "mode": info.get("mode", "cached"),
            "track": track.as_dict(),
            "content_type": info.get("content_type"),
            "bytes": info.get("bytes"),
            "cue_count": len(cues),
            "first": (
                {"start": cues[0].start, "end": cues[0].end, "text": cues[0].text}
                if cues else None
            ),
            "last": (
                {"start": cues[-1].start, "end": cues[-1].end, "text": cues[-1].text}
                if cues else None
            ),
            "media_duration": duration or None,
            "coverage": (cues[-1].end / duration) if cues and duration else None,
        }

    def subtitle_cues(
        self,
        session: PlaybackSession,
        track: SubtitleTrack,
        position: float,
    ) -> tuple[Cue, ...]:
        # position is intentionally unused: once the complete subtitle is
        # cached, seeks and playback progression only change which cue the
        # caller selects from the immutable timeline.
        return self._load_subtitle_cues(session, track)

    def diagnostics(self) -> dict:
        plex = self._plex()
        with self._lock:
            cached_subtitles = len(self._subtitle_cache)
        return {
            "provider": self.name,
            "connected": True,
            "server": getattr(plex, "friendlyName", None),
            "base_url": self.base_url,
            "cached_subtitles": cached_subtitles,
        }
