"""Subtitle data model and SRT / VTT serialisation."""

from __future__ import annotations

from dataclasses import asdict, dataclass


@dataclass
class Segment:
    id: int
    start: float
    end: float
    text: str  # original recognised text
    zh: str = ""
    en: str = ""

    def to_dict(self) -> dict:
        return asdict(self)


def _timestamp(seconds: float, sep: str) -> str:
    ms = max(0, int(round(seconds * 1000)))
    h, ms = divmod(ms, 3_600_000)
    m, ms = divmod(ms, 60_000)
    s, ms = divmod(ms, 1000)
    return f"{h:02d}:{m:02d}:{s:02d}{sep}{ms:03d}"


def _lines(seg: Segment, mode: str) -> list[str]:
    zh = seg.zh or seg.text
    en = seg.en or seg.text
    if mode == "zh":
        return [zh]
    if mode == "en":
        return [en]
    if mode == "bilingual":
        return [zh, en] if zh != en else [zh]
    raise ValueError(f"unknown subtitle mode: {mode}")


def to_srt(segments: list[Segment], mode: str = "bilingual") -> str:
    blocks = []
    for i, seg in enumerate(segments, 1):
        blocks.append(
            f"{i}\n{_timestamp(seg.start, ',')} --> {_timestamp(seg.end, ',')}\n"
            + "\n".join(_lines(seg, mode))
        )
    return "\n\n".join(blocks) + "\n"


def to_vtt(segments: list[Segment], mode: str = "bilingual") -> str:
    blocks = ["WEBVTT"]
    for seg in segments:
        blocks.append(
            f"{_timestamp(seg.start, '.')} --> {_timestamp(seg.end, '.')}\n"
            + "\n".join(_lines(seg, mode))
        )
    return "\n\n".join(blocks) + "\n"
