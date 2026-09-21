from __future__ import annotations

import logging
import math
import threading
import time
import uuid

import requests
from plexapi.server import PlexServer

from app.domain import Cue, PlaybackSession, SubtitleTrack
from app.providers.base import MediaProvider
from app.subtitle_player import SubtitlePlayer
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
        self._players: dict[tuple[str, str, str], SubtitlePlayer] = {}
        self._track_cache: dict[str, tuple[float, list[SubtitleTrack]]] = {}
        self._external_cache: dict[tuple[str, str], tuple[Cue, ...]] = {}
        self._track_cache_ttl = 30.0
        self._player_idle_ttl = 90.0

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

    def _request_params(
        self,
        rating_key: str,
        stream_id: int,
        offset: float,
        transcode_session: str,
    ) -> dict:
        return {
            "hasMDE": 1,
            "path": f"/library/metadata/{rating_key}",
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
    def _selected_stream_id(part) -> int:
        selected = [stream for stream in part.subtitleStreams() if getattr(stream, "selected", False)]
        return int(getattr(selected[0], "id")) if selected else 0

    def _select_stream(self, part_id: int, stream_id: int) -> None:
        response = requests.put(
            f"{self.base_url}/library/parts/{part_id}",
            params={"subtitleStreamID": stream_id, "X-Plex-Token": self.token},
            timeout=5,
        )
        response.raise_for_status()

    def _fetch_external(self, session: PlaybackSession, track: SubtitleTrack) -> tuple[Cue, ...]:
        cache_key = (session.rating_key, track.id)
        with self._lock:
            cached = self._external_cache.get(cache_key)
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
        with self._lock:
            self._external_cache[cache_key] = cues
        return cues

    def _start_dash_transcode(self, params: dict, headers: dict) -> None:
        """Kick the same DASH session used by Plex Web.

        PMS exposes subtitles through the universal subtitle endpoint, but the
        underlying transcoder is started by the companion start.mpd request.
        Without this request Plex can yield only the initial subtitle fragment
        and then stall.
        """
        response = requests.get(
            f"{self.base_url}/video/:/transcode/universal/start.mpd",
            params=params,
            headers=headers,
            timeout=10,
        )
        response.raise_for_status()
        logger.debug(
            "Started Plex DASH transcode session=%s bytes=%s",
            params.get("session"),
            len(response.content),
        )

    def _stop_transcode(self, transcode_session: str) -> None:
        try:
            response = requests.get(
                f"{self.base_url}/video/:/transcode/universal/stop",
                params={"session": transcode_session, "X-Plex-Token": self.token},
                timeout=3,
            )
            response.raise_for_status()
            logger.debug("Stopped Plex transcode session=%s", transcode_session)
        except Exception as exc:
            logger.warning("Unable to stop Plex transcode session=%s: %s", transcode_session, exc)

    def _read_subtitle_segment(
        self,
        response: requests.Response,
        stop_event: threading.Event,
        max_bytes: int = 65536,
    ) -> bytes:
        """Read enough of one PMS subtitle response to include timed text.

        Plex ASS responses begin with a sizeable script/style header. Reading
        only the first HTTP chunk can therefore return no Dialogue lines at all.
        Accumulate until at least one cue parses, the response ends, or a sane
        safety limit is reached.
        """
        payload = bytearray()
        for chunk in response.iter_content(chunk_size=4096):
            if stop_event.is_set():
                break
            if not chunk:
                continue
            payload.extend(chunk)

            text = bytes(payload).decode("utf-8", errors="replace")
            if parse_timed_text(text):
                break
            if len(payload) >= max_bytes:
                break
        return bytes(payload)

    def _run_embedded_stream(
        self,
        rating_key: str,
        part_id: int,
        stream_id: int,
        start_position: float,
        stop_event: threading.Event,
        on_payload,
    ) -> None:
        transcode_session = uuid.uuid4().hex[:24]
        playback_session_id = uuid.uuid4().hex
        headers = self._request_headers(playback_session_id)
        params = self._request_params(rating_key, stream_id, start_position, transcode_session)

        old_stream_id = 0
        changed_global_selection = False

        # Establish one Plex transcode transport. PMS may stop advancing a
        # transport after some subtitle cues; SubtitlePlayer remains persistent
        # while stalled Plex transports are recycled below.
        with self._transcode_lock:
            item = self._plex().fetchItem(int(rating_key))
            media = list(getattr(item, "media", []) or [])
            if not media or not getattr(media[0], "parts", None):
                raise RuntimeError("Plex media item has no parts")
            part = media[0].parts[0]
            old_stream_id = self._selected_stream_id(part)

            if old_stream_id != stream_id:
                self._select_stream(part_id, stream_id)
                changed_global_selection = True

            decision = requests.get(
                f"{self.base_url}/video/:/transcode/universal/decision",
                params=params,
                headers=headers,
                timeout=10,
            )
            decision.raise_for_status()

            # Start the companion DASH transcode just as Plex Web does.
            self._start_dash_transcode(params, headers)

            # Prime the first subtitle payload while the requested stream is
            # still selected on the Plex Part.
            first_response = None
            try:
                first_response = requests.get(
                    f"{self.base_url}/video/:/transcode/universal/subtitles",
                    params=params,
                    headers=headers,
                    stream=True,
                    timeout=(5, 5),
                )
                first_response.raise_for_status()
                first_payload = self._read_subtitle_segment(first_response, stop_event)
                if first_payload:
                    first_cues = parse_timed_text(first_payload.decode("utf-8", errors="replace"))
                    on_payload(first_payload)
                    if first_cues:
                        params["offset"] = max(
                            int(params["offset"]),
                            math.ceil(first_cues[-1].end) + 1,
                        )
                    logger.debug(
                        "Plex subtitle segment session=%s request=1 bytes=%s cues=%s next_offset=%s",
                        transcode_session,
                        len(first_payload),
                        len(first_cues),
                        params["offset"],
                    )
            finally:
                if first_response is not None:
                    first_response.close()
                if changed_global_selection:
                    try:
                        self._select_stream(part_id, old_stream_id)
                    except Exception:
                        logger.exception("Unable to restore Plex subtitle stream %s", old_stream_id)

        logger.debug(
            "Opened Plex subtitle polling session rating_key=%s stream=%s offset=%.1f session=%s",
            rating_key,
            stream_id,
            start_position,
            transcode_session,
        )

        request_count = 1
        last_cue_signature = None
        repeated_cue_count = 0
        try:
            while not stop_event.is_set():
                response = None
                received = False
                try:
                    response = requests.get(
                        f"{self.base_url}/video/:/transcode/universal/subtitles",
                        params=params,
                        headers=headers,
                        stream=True,
                        timeout=(5, 5),
                    )
                    response.raise_for_status()
                    request_count += 1

                    payload = self._read_subtitle_segment(response, stop_event)
                    if payload:
                        segment_cues = parse_timed_text(payload.decode("utf-8", errors="replace"))
                        on_payload(payload)
                        received = True
                        if segment_cues:
                            last_cue = segment_cues[-1]
                            signature = (
                                round(last_cue.start, 3),
                                round(last_cue.end, 3),
                                last_cue.text,
                            )
                            if signature == last_cue_signature:
                                repeated_cue_count += 1
                            else:
                                last_cue_signature = signature
                                repeated_cue_count = 0

                            params["offset"] = max(
                                int(params["offset"]),
                                math.ceil(last_cue.end) + 1,
                            )
                        logger.debug(
                            "Plex subtitle segment session=%s request=%s bytes=%s cues=%s next_offset=%s repeats=%s",
                            transcode_session,
                            request_count,
                            len(payload),
                            len(segment_cues),
                            params["offset"],
                            repeated_cue_count,
                        )

                        # PMS can pin a transcode session to its original
                        # subtitle position and then return the same cue
                        # forever even when the request offset is advanced.
                        # End only this transport session after a few duplicate
                        # responses. SubtitlePlayer keeps its accumulated
                        # timeline and will open a fresh PMS session on the next
                        # status poll from the current playback position.
                        if segment_cues and repeated_cue_count >= 3:
                            logger.info(
                                "Recycling stalled Plex subtitle session=%s repeated_cue=%.3f-%.3f next_offset=%s",
                                transcode_session,
                                segment_cues[-1].start,
                                segment_cues[-1].end,
                                params["offset"],
                            )
                            break

                except (requests.exceptions.ReadTimeout, requests.exceptions.ConnectionError):
                    # No subtitle became available during this poll. Reopen the
                    # same endpoint/session; this is normal during quiet scenes.
                    pass
                finally:
                    if response is not None:
                        response.close()

                if not received:
                    stop_event.wait(0.10)

        finally:
            self._stop_transcode(transcode_session)
            logger.debug(
                "Closed Plex subtitle polling session rating_key=%s stream=%s requests=%s",
                rating_key,
                stream_id,
                request_count,
            )

    def _player_key(self, session: PlaybackSession, track: SubtitleTrack) -> tuple[str, str, str]:
        return (session.player_id, session.rating_key, track.id)

    def _get_player(self, session: PlaybackSession, track: SubtitleTrack) -> SubtitlePlayer:
        key = self._player_key(session, track)
        stale: list[SubtitlePlayer] = []

        with self._lock:
            for existing_key, player in list(self._players.items()):
                same_client = existing_key[0] == session.player_id
                expired = time.monotonic() - player.last_used_at > self._player_idle_ttl
                if expired or (same_client and existing_key != key):
                    stale.append(self._players.pop(existing_key))

            player = self._players.get(key)
            if player is None:
                rating_key = session.rating_key
                part_id = int(track.provider_data["part_id"])
                stream_id = int(track.provider_data["stream_id"])

                def runner(start_position, stop_event, on_payload):
                    self._run_embedded_stream(
                        rating_key,
                        part_id,
                        stream_id,
                        start_position,
                        stop_event,
                        on_payload,
                    )

                player = SubtitlePlayer(runner)
                self._players[key] = player

        for old_player in stale:
            old_player.stop(join_timeout=0.2)
        return player

    def subtitle_cues(
        self,
        session: PlaybackSession,
        track: SubtitleTrack,
        position: float,
    ) -> tuple[Cue, ...]:
        if not track.compatible:
            return ()

        if track.source == "external" and track.provider_data.get("key"):
            return self._fetch_external(session, track)

        player = self._get_player(session, track)
        return player.sync(position, session.state)

    def diagnostics(self) -> dict:
        plex = self._plex()
        with self._lock:
            active_players = len(self._players)
        return {
            "provider": self.name,
            "connected": True,
            "server": getattr(plex, "friendlyName", None),
            "base_url": self.base_url,
            "subtitle_players": active_players,
        }
