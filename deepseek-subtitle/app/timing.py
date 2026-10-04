"""Subtitle timing and line layout following the Netflix Timed Text Style Guide.

Rules applied (Netflix general requirements plus the English and Simplified
Chinese guides):

- minimum duration 5/6 s (20 frames at 24 fps), maximum 7 s;
- reading speed: a subtitle stays up long enough to read it, at most
  17 characters per second for English and 9 for Chinese, extended into the
  following silence when needed;
- at least 2 frames between consecutive subtitles; gaps shorter than 0.5 s
  are closed to those 2 frames, so subtitles chain instead of flickering;
- times are snapped to the video's frame grid;
- at most 42 characters per line for English and 16 for Chinese, in at most
  two lines, broken at a natural point with the bottom line the longer one.
"""

from __future__ import annotations

import re

from .subtitles import Segment

MIN_DURATION = 5 / 6
MAX_DURATION = 7.0
CHAIN_GAP = 0.5
GAP_FRAMES = 2
DEFAULT_FPS = 24.0
CPS = {"zh": 9.0, "en": 17.0}
LINE_CHARS = {"zh": 16, "en": 42}

_CJK = re.compile(r"[　-鿿가-힯＀-￯]")
_SPACE = re.compile(r"\s+")


def is_cjk(text: str) -> bool:
    return len(_CJK.findall(text)) > len(text) / 3


def reading_chars(text: str) -> int:
    """Characters counted for reading speed: Chinese counts characters
    (punctuation excluded), English counts letters, digits, spaces and punctuation."""
    if is_cjk(text):
        return len(re.findall(r"[\w㐀-鿿]", text))
    return len(_SPACE.sub(" ", text.strip()))


def reading_time(seg: Segment, langs=("zh", "en")) -> float:
    """Time needed to read every displayed line of the subtitle."""
    needed = 0.0
    texts = {"zh": seg.zh, "en": seg.en}
    for lang in langs:
        text = texts.get(lang) or ""
        if text:
            needed = max(needed, reading_chars(text) / CPS["zh" if is_cjk(text) else "en"])
    if not needed and seg.text:
        needed = reading_chars(seg.text) / CPS["zh" if is_cjk(seg.text) else "en"]
    return needed


def netflix_timing(segments: list[Segment], fps: float | None = None, langs=("zh", "en")) -> list[Segment]:
    """Adjust start/end times in place; returns the segments sorted by start."""
    fps = fps if fps and 1 <= fps <= 240 else DEFAULT_FPS
    frame = 1.0 / fps
    gap = GAP_FRAMES * frame
    snap = lambda t: round(round(t / frame) * frame, 3)  # noqa: E731
    segs = sorted(segments, key=lambda s: s.start)

    for i, seg in enumerate(segs):
        seg.start = snap(max(0.0, seg.start))
        nxt = segs[i + 1].start if i + 1 < len(segs) else None
        limit = snap(nxt) - gap if nxt is not None else float("inf")

        wanted = max(seg.end, seg.start + MIN_DURATION, seg.start + reading_time(seg, langs))
        wanted = min(wanted, max(seg.end, seg.start + MAX_DURATION))  # only extend up to 7 s
        end = min(wanted, limit)
        if nxt is not None and limit - end < CHAIN_GAP:
            end = limit  # close small gaps: chain to 2 frames before the next subtitle
        seg.end = max(snap(end), seg.start + frame)
    return segs


def _break_points(text: str, cjk: bool) -> list[int]:
    if cjk:
        return list(range(1, len(text)))
    return [m.start() for m in re.finditer(" ", text)]


def wrap(text: str) -> list[str]:
    """Split a line that is too long into two, Netflix style: at a natural
    break near the middle, preferring a shorter top line ("bottom-heavy")."""
    text = text.strip()
    cjk = is_cjk(text)
    limit = LINE_CHARS["zh" if cjk else "en"]
    if len(text) <= limit:
        return [text]
    best, best_score = None, float("inf")
    for i in _break_points(text, cjk):
        top, bottom = text[:i].rstrip(), text[i:].lstrip()
        if not top or not bottom:
            continue
        score = abs(len(top) - len(bottom))
        if len(top) > len(bottom):
            score += 2  # prefer the pyramid shape
        if top[-1] in ",;:，、；：。.!?！？":
            score -= 6  # break after punctuation
        if max(len(top), len(bottom)) > limit:
            score += 50
        if score < best_score:
            best, best_score = (top, bottom), score
    return list(best) if best else [text]
