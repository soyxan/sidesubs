from __future__ import annotations

import logging
import threading
import time
import uuid
from collections import OrderedDict

import requests
from plexapi.server import PlexServer

from app.domain import Cue, PlaybackSession, SubtitleTrack
from app.providers.base import MediaProvider
from app.subtitles import infer_language, merge_cues, parse_timed_text


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
        self._window_cache: OrderedDict[tuple[str, str, int], tuple[float, tuple[Cue, ...]]] = OrderedDict()
        self._cache_ttl = 20.0
        self._cache_limit = 256
        self._prefetching: set[tuple[str, str, int]] = set()

    def _plex(self) -> PlexServer:
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

    def _request_params(self, session: PlaybackSession, stream_id: int, offset: int, transcode_session: str) -> dict:
        return {
            "hasMDE": 1,
            "path": f"/library/metadata/{session.rating_key}",
            "mediaIndex": 0,
            "partIndex": 0,
            "protocol": "dash",
            "fastSeek": 1,
            "directPlay": 0,
            "directStream": 1,
            "subtitleSize": 100,
            "audioBoost": 100,
            "location": "lan",
            "addDebugOverlay": 0,
            "autoAdjustQuality": 0,
            "directStreamAudio": 0,
            "autoAdjustSubtitle": 1,
            "mediaBufferSize": 102400,
            "session": transcode_session,
            "subtitles": "auto",
            "subtitleStreamID": stream_id,
            "offset": max(0, int(offset)),
            "copyts": 1,
            "Accept-Language": "en",
            "X-Plex-Token": self.token,
        }

    @staticmethod
    def _decision_contains_stream(payload, stream_id: int) -> bool:
        if isinstance(payload, dict):
            if str(payload.get("id", "")) == str(stream_id) and payload.get("streamType") == 3:
                return True
            return any(PlexProvider._decision_contains_stream(value, stream_id) for value in payload.values())
        if isinstance(payload, list):
            return any(PlexProvider._decision_contains_stream(value, stream_id) for value in payload)
        return False

    def _selected_stream_id(self, part) -> int:
        selected = [s for s in part.subtitleStreams() if getattr(s, "selected", False)]
        return int(getattr(selected[0], "id")) if selected else 0

    def _select_stream(self, part_id: int, stream_id: int) -> None:
        response = requests.put(
            f"{self.base_url}/library/parts/{part_id}",
            params={"subtitleStreamID": stream_id, "X-Plex-Token": self.token},
            timeout=5,
        )
        response.raise_for_status()

    def _fetch_external(self, track: SubtitleTrack) -> tuple[Cue, ...]:
        key = track.provider_data.get("key")
        if not key:
            return ()
        response = requests.get(
            f"{self.base_url}{key}",
            params={"X-Plex-Token": self.token, "encoding": "utf-8", "format": "srt"},
            timeout=10,
        )
        response.raise_for_status()
        return parse_timed_text(response.text)

    def _read_first_subtitle_chunk(self, response: requests.Response) -> bytes:
        for chunk in response.iter_content(chunk_size=8192):
            if chunk:
                return chunk
        return b""

    def _fetch_embedded_at_offset(
        self,
        session: PlaybackSession,
        track: SubtitleTrack,
        offset: int,
    ) -> tuple[Cue, ...]:
        with self._transcode_lock:
            return self._fetch_embedded_at_offset_locked(session, track, offset)

    def _fetch_embedded_at_offset_locked(
        self,
        session: PlaybackSession,
        track: SubtitleTrack,
        offset: int,
    ) -> tuple[Cue, ...]:
        stream_id = int(track.provider_data["stream_id"])
        part_id = int(track.provider_data["part_id"])
        transcode_session = uuid.uuid4().hex[:24]
        playback_session_id = uuid.uuid4().hex
        headers = self._request_headers(playback_session_id)
        params = self._request_params(session, stream_id, offset, transcode_session)

        _, part = self._item_part(session)
        old_stream_id = self._selected_stream_id(part)
        changed_global_selection = False

        try:
            decision = requests.get(
                f"{self.base_url}/video/:/transcode/universal/decision",
                params=params,
                headers=headers,
                timeout=10,
            )
            decision.raise_for_status()
            try:
                has_stream = self._decision_contains_stream(decision.json(), stream_id)
            except ValueError:
                has_stream = False

            # PMS versions differ in whether subtitleStreamID in the universal
            # transcode query is honored. Fall back to the documented Part
            # selection only for the short extraction transaction, then restore.
            if not has_stream:
                self._select_stream(part_id, stream_id)
                changed_global_selection = old_stream_id != stream_id
                decision = requests.get(
                    f"{self.base_url}/video/:/transcode/universal/decision",
                    params=params,
                    headers=headers,
                    timeout=10,
                )
                decision.raise_for_status()

            try:
                with requests.get(
                    f"{self.base_url}/video/:/transcode/universal/subtitles",
                    params=params,
                    headers=headers,
                    stream=True,
                    timeout=(4, 3),
                ) as response:
                    response.raise_for_status()
                    payload = self._read_first_subtitle_chunk(response)
            except (requests.exceptions.ReadTimeout, requests.exceptions.ConnectionError):
                # A quiet dialogue interval can leave PMS' chunked subtitle
                # response open without producing a chunk before our read timeout.
                # That is not a subtitle-track failure: it simply means this
                # requested window contained no immediately available cue.
                logger.debug(
                    "Plex subtitle window timed out rating_key=%s stream=%s offset=%s",
                    session.rating_key,
                    stream_id,
                    offset,
                )
                return ()

            if not payload:
                return ()
            text = payload.decode("utf-8", errors="replace")
            cues = parse_timed_text(text)
            logger.debug(
                "Plex subtitle window rating_key=%s stream=%s offset=%s bytes=%s cues=%s",
                session.rating_key,
                stream_id,
                offset,
                len(payload),
                len(cues),
            )
            return cues
        finally:
            if changed_global_selection:
                try:
                    self._select_stream(part_id, old_stream_id)
                except Exception:
                    logger.exception("Unable to restore Plex subtitle stream %s", old_stream_id)

    def _cache_key(self, session: PlaybackSession, track: SubtitleTrack, offset: int) -> tuple[str, str, int]:
        return (session.rating_key, track.id, offset)

    def _get_cached_window(self, session: PlaybackSession, track: SubtitleTrack, offset: int) -> tuple[Cue, ...] | None:
        key = self._cache_key(session, track, offset)
        now = time.monotonic()
        with self._lock:
            cached = self._window_cache.get(key)
            if cached and now - cached[0] <= self._cache_ttl:
                self._window_cache.move_to_end(key)
                return cached[1]
        return None

    def _store_window(self, session: PlaybackSession, track: SubtitleTrack, offset: int, cues: tuple[Cue, ...]) -> None:
        key = self._cache_key(session, track, offset)
        now = time.monotonic()
        with self._lock:
            self._window_cache[key] = (now, cues)
            self._window_cache.move_to_end(key)
            while len(self._window_cache) > self._cache_limit:
                self._window_cache.popitem(last=False)

    def _cached_window(self, session: PlaybackSession, track: SubtitleTrack, offset: int) -> tuple[Cue, ...]:
        cached = self._get_cached_window(session, track, offset)
        if cached is not None:
            return cached
        cues = self._fetch_embedded_at_offset(session, track, offset)
        self._store_window(session, track, offset, cues)
        return cues

    def _prefetch_window(self, session: PlaybackSession, track: SubtitleTrack, offset: int) -> None:
        if self._get_cached_window(session, track, offset) is not None:
            return

        key = self._cache_key(session, track, offset)
        with self._lock:
            if key in self._prefetching:
                return
            self._prefetching.add(key)

        def worker() -> None:
            try:
                cues = self._fetch_embedded_at_offset(session, track, offset)
                self._store_window(session, track, offset, cues)
            except Exception:
                logger.debug(
                    "Plex subtitle prefetch failed rating_key=%s track=%s offset=%s",
                    session.rating_key,
                    track.id,
                    offset,
                    exc_info=True,
                )
            finally:
                with self._lock:
                    self._prefetching.discard(key)

        threading.Thread(
            target=worker,
            name=f"sidesubs-subtitle-prefetch-{session.rating_key}-{offset}",
            daemon=True,
        ).start()

    def load_subtitle_window(
        self,
        session: PlaybackSession,
        track: SubtitleTrack,
        position: float,
    ) -> tuple[Cue, ...]:
        if not track.compatible:
            return ()
        if track.source == "external" and track.provider_data.get("key"):
            return self._fetch_external(track)

        # PMS can take a few seconds to materialize a subtitle chunk. Never
        # make cue presentation wait for that work: keep a predictive rolling
        # cache ahead of playback and return whatever is already available.
        #
        # Four-second buckets limit transcode churn while look-ahead requests
        # at +4/+8/+12 seconds give PMS time to prepare cues before playback
        # reaches them.
        bucket = max(0, int(position // 4) * 4)
        offsets = [
            max(0, bucket - 4),
            bucket,
            bucket + 4,
            bucket + 8,
            bucket + 12,
        ]

        groups: list[tuple[Cue, ...]] = []
        missing: list[int] = []
        for offset in offsets:
            cached = self._get_cached_window(session, track, offset)
            if cached is None:
                missing.append(offset)
            else:
                groups.append(cached)

        # Cold start: fetch only one nearby window synchronously. All other PMS
        # work happens in background so normal /api/status polling stays fast.
        if not groups:
            initial_offset = max(0, bucket - 2)
            try:
                groups.append(self._cached_window(session, track, initial_offset))
            except requests.RequestException:
                logger.debug(
                    "Initial Plex subtitle fetch failed rating_key=%s track=%s offset=%s",
                    session.rating_key,
                    track.id,
                    initial_offset,
                    exc_info=True,
                )

        for offset in missing:
            self._prefetch_window(session, track, offset)

        return merge_cues(*groups)

    def diagnostics(self) -> dict:
        plex = self._plex()
        return {
            "provider": self.name,
            "connected": True,
            "server": getattr(plex, "friendlyName", None),
            "base_url": self.base_url,
        }
