from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass
class PlaybackSession:
    id: str
    player_id: str
    client: str
    product: str
    device: str
    state: str
    title: str
    position: float
    rating_key: str
    native: Any = field(default=None, repr=False, compare=False)


@dataclass(frozen=True)
class SubtitleTrack:
    id: str
    source: str
    language: str | None
    title: str
    codec: str | None
    compatible: bool
    provider_data: dict[str, Any] = field(default_factory=dict, repr=False, compare=False)

    def as_dict(self) -> dict:
        return {
            "id": self.id,
            "source": self.source,
            "language": self.language,
            "title": self.title,
            "codec": self.codec,
            "compatible": self.compatible,
        }


@dataclass(frozen=True)
class Cue:
    start: float
    end: float
    text: str
