from __future__ import annotations

from abc import ABC, abstractmethod

from app.domain import Cue, PlaybackSession, SubtitleTrack


class MediaProvider(ABC):
    """Provider boundary used by SideSubs.

    Plex, Emby and Jellyfin adapters should translate their native APIs into
    these provider-neutral models. UI/session/subtitle logic stays outside.
    """

    name: str

    @abstractmethod
    def list_sessions(self) -> list[PlaybackSession]:
        raise NotImplementedError

    @abstractmethod
    def list_subtitle_tracks(self, session: PlaybackSession) -> list[SubtitleTrack]:
        raise NotImplementedError

    @abstractmethod
    def load_subtitle_window(
        self,
        session: PlaybackSession,
        track: SubtitleTrack,
        position: float,
    ) -> tuple[Cue, ...]:
        """Return enough nearby cues to resolve current + next subtitle."""
        raise NotImplementedError

    @abstractmethod
    def diagnostics(self) -> dict:
        raise NotImplementedError
