from __future__ import annotations

from abc import ABC, abstractmethod

from app.domain import Cue, PlaybackSession, SubtitleTrack


class MediaProvider(ABC):
    """Provider boundary used by SideSubs.

    Plex, Emby and Jellyfin adapters translate their native APIs into these
    provider-neutral models. Session selection, cue timing and UI stay outside
    the provider implementation.
    """

    name: str

    @abstractmethod
    def list_sessions(self) -> list[PlaybackSession]:
        raise NotImplementedError

    @abstractmethod
    def list_subtitle_tracks(self, session: PlaybackSession) -> list[SubtitleTrack]:
        raise NotImplementedError

    @abstractmethod
    def subtitle_cues(
        self,
        session: PlaybackSession,
        track: SubtitleTrack,
        position: float,
    ) -> tuple[Cue, ...]:
        """Return the complete timed-text cue timeline for the selected track."""
        raise NotImplementedError

    @abstractmethod
    def diagnostics(self) -> dict:
        raise NotImplementedError
