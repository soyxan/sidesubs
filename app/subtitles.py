from __future__ import annotations

import html
import re
from bisect import bisect_right

from app.domain import Cue


TAG_RE = re.compile(r"<[^>]+>")
ASS_OVERRIDE_RE = re.compile(r"\{\\[^}]*\}")

LANGUAGE_ALIASES = {
    "english": "en", "eng": "en",
    "spanish": "es", "espanol": "es", "español": "es", "castellano": "es", "spa": "es", "esp": "es",
    "french": "fr", "fra": "fr", "fre": "fr",
    "german": "de", "deu": "de", "ger": "de",
    "italian": "it", "ita": "it",
    "portuguese": "pt", "por": "pt",
    "dutch": "nl", "nld": "nl", "dut": "nl",
    "japanese": "ja", "jpn": "ja",
    "korean": "ko", "kor": "ko",
    "chinese": "zh", "zho": "zh", "chi": "zh",
    "arabic": "ar", "ara": "ar",
    "russian": "ru", "rus": "ru",
}


def normalize_language(value: str | None) -> str:
    raw = (value or "").strip().casefold().replace("_", "-")
    if not raw:
        return ""
    raw = LANGUAGE_ALIASES.get(raw, raw)
    primary = raw.split("-", 1)[0]
    return LANGUAGE_ALIASES.get(primary, primary)


def infer_language(value: str | None) -> str:
    text = (value or "").strip().casefold()
    direct = normalize_language(text)
    if direct in set(LANGUAGE_ALIASES.values()):
        return direct
    for token in re.split(r"[^a-zA-ZÀ-ÿ]+", text):
        normalized = normalize_language(token)
        if token in LANGUAGE_ALIASES or normalized in set(LANGUAGE_ALIASES.values()):
            return normalized
    return ""


def clean_subtitle_text(value: str) -> str:
    value = value.replace(r"\N", "\n").replace(r"\n", "\n")
    value = ASS_OVERRIDE_RE.sub("", value)
    value = TAG_RE.sub("", value)
    return html.unescape(value).strip()


def _parse_srt_timestamp(value: str) -> float:
    hours, minutes, rest = value.strip().split(":")
    seconds, milliseconds = rest.replace(".", ",").split(",", 1)
    return int(hours) * 3600 + int(minutes) * 60 + int(seconds) + int(milliseconds[:3].ljust(3, "0")) / 1000


def _parse_ass_timestamp(value: str) -> float:
    hours, minutes, seconds = value.strip().split(":")
    return int(hours) * 3600 + int(minutes) * 60 + float(seconds)


def parse_timed_text(text: str) -> tuple[Cue, ...]:
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    if "[Events]" in normalized and "Dialogue:" in normalized:
        return parse_ass_text(normalized)
    return parse_srt_or_vtt_text(normalized)


def parse_srt_or_vtt_text(text: str) -> tuple[Cue, ...]:
    cues: list[Cue] = []
    for block in re.split(r"\n{2,}", text.strip()):
        lines = [line for line in block.splitlines() if line.strip()]
        timing_index = next((i for i, line in enumerate(lines) if "-->" in line), None)
        if timing_index is None:
            continue
        try:
            start_raw, end_raw = (part.strip() for part in lines[timing_index].split("-->", 1))
            end_raw = end_raw.split()[0]
            subtitle_text = clean_subtitle_text("\n".join(lines[timing_index + 1:]))
            if subtitle_text:
                cues.append(Cue(_parse_srt_timestamp(start_raw), _parse_srt_timestamp(end_raw), subtitle_text))
        except (ValueError, IndexError):
            continue
    return _dedupe_sorted(cues)


def parse_ass_text(text: str) -> tuple[Cue, ...]:
    cues: list[Cue] = []
    in_events = False
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if line == "[Events]":
            in_events = True
            continue
        if line.startswith("[") and line != "[Events]":
            in_events = False
        if not in_events or not line.startswith("Dialogue:"):
            continue
        try:
            fields = line[len("Dialogue:"):].lstrip().split(",", 9)
            if len(fields) < 10:
                continue
            start = _parse_ass_timestamp(fields[1])
            end = _parse_ass_timestamp(fields[2])
            subtitle_text = clean_subtitle_text(fields[9])
            if subtitle_text:
                cues.append(Cue(start, end, subtitle_text))
        except (ValueError, IndexError):
            continue
    return _dedupe_sorted(cues)


def merge_cues(*groups: tuple[Cue, ...]) -> tuple[Cue, ...]:
    return _dedupe_sorted([cue for group in groups for cue in group])


def _dedupe_sorted(cues) -> tuple[Cue, ...]:
    unique: dict[tuple[float, float, str], Cue] = {}
    for cue in cues:
        unique[(round(cue.start, 3), round(cue.end, 3), cue.text)] = cue
    return tuple(sorted(unique.values(), key=lambda cue: (cue.start, cue.end, cue.text)))


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
