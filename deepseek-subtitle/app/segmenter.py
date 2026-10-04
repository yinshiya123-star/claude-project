"""Regroup Whisper's word timestamps into subtitle-sized sentences.

Whisper cuts segments at arbitrary points, often mid-sentence. Following the
approach of VideoLingo (https://github.com/Huanshere/VideoLingo, Apache-2.0),
words are first joined into whole sentences, then sentences that are too long
for one subtitle are split at the most natural point near the middle: after a
comma or at a pause. VideoLingo uses spaCy and an LLM for this; here word
timings and punctuation do the job, so no extra models are needed.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from .subtitles import Segment

SENTENCE_END = tuple(".?!。？！…")
CLAUSE_BREAK = tuple(",;:，；：")
LIST_BREAK = tuple("、")  # enumeration comma: a weaker split point than a real comma
PAUSE = 1.2  # seconds of silence that end a sentence
# Netflix limits: 7 s, two lines (2 x 42 English / 2 x 16 Chinese characters).
# Sources are kept a little under two English lines, leaving room for the translation.
MAX_LEN = 70  # weighted length (see text_len) of one subtitle
MAX_DURATION = 7.0  # seconds


@dataclass
class Word:
    start: float
    end: float
    text: str  # as Whisper gives it, Latin words keep their leading space


def text_len(text: str) -> float:
    """Display width (VideoLingo's calc_len idea): a CJK character weighs 2.2, so
    MAX_LEN allows 70 Latin or about 32 Chinese characters (Netflix: 2 x 16)."""
    width = 0.0
    for ch in text:
        code = ord(ch)
        if 0x3000 <= code <= 0x9FFF or 0xAC00 <= code <= 0xD7AF or 0xFF00 <= code <= 0xFFEF:
            width += 2.2
        else:
            width += 1
    return width


_BREAK = "".join(SENTENCE_END + CLAUSE_BREAK + LIST_BREAK)
# punctuation followed by more text inside one token, e.g. "。我" or ".I"
_GLUED = re.compile(rf"(?<=[{re.escape(_BREAK)}])(?=[^\s{re.escape(_BREAK)}\"'”’」』）)])")
_CJK = re.compile(r"[\u3040-\u30ff\u3400-\u9fff\uac00-\ud7af]")


def _unglue(words: list[Word]) -> list[Word]:
    """Whisper tokens can carry punctuation and the next sentence's first
    character together ("。我"): split them, sharing the time by length, so the
    character isn't left hanging at the end of the previous subtitle."""
    out = []
    for w in words:
        parts = _GLUED.split(w.text)
        if len(parts) == 1:
            out.append(w)
            continue
        total = sum(len(p.strip()) or 1 for p in parts)
        t = w.start
        for i, part in enumerate(parts):
            share = (w.end - w.start) * (len(part.strip()) or 1) / total
            if i and not _CJK.search(part[:1]) and not part.startswith(" "):
                part = " " + part  # "fun.I" -> "fun." + " I"
            out.append(Word(t, t + share, part))
            t += share
    return out


def _bare(words: list[Word]) -> str:
    return re.sub(rf"[\s{re.escape(_BREAK)}\"'“”‘’「」『』（）()]", "", _join(words))


def _orphan(words: list[Word]) -> bool:
    """A lone character (or one short Latin word) that isn't a sentence of its own."""
    bare = _bare(words)
    return len(bare) <= 1 or (bare.isascii() and len(words) == 1 and len(bare) <= 3)


def _merge_orphans(groups: list[list[Word]]) -> list[list[Word]]:
    """Attach stray one-character pieces (e.g. split off by a pause) to the
    sentence they belong to; complete short lines like "好。" stay on their own."""
    out: list[list[Word]] = []
    i = 0
    while i < len(groups):
        group = groups[i]
        nxt = groups[i + 1] if i + 1 < len(groups) else None
        if _orphan(group):
            ends = _join(group).endswith(SENTENCE_END)
            if out and not _join(out[-1]).endswith(SENTENCE_END):
                out[-1] = out[-1] + group  # the end of the previous, unfinished sentence
                i += 1
                continue
            if nxt is not None and not ends and nxt[0].start - group[-1].end < PAUSE:
                groups[i + 1] = group + nxt  # the start of the next sentence
                i += 1
                continue
        out.append(group)
        i += 1
    return out


def _join(words: list[Word]) -> str:
    return "".join(w.text for w in words).strip()


def _sentences(words: list[Word]) -> list[list[Word]]:
    sentences, current = [], []
    for i, word in enumerate(words):
        current.append(word)
        next_word = words[i + 1] if i + 1 < len(words) else None
        ends = word.text.strip().endswith(SENTENCE_END)
        paused = next_word is not None and next_word.start - word.end >= PAUSE
        if ends or paused or next_word is None:
            sentences.append(current)
            current = []
    return sentences


def _too_long(words: list[Word]) -> bool:
    return len(words) > 1 and (
        text_len(_join(words)) > MAX_LEN or words[-1].end - words[0].start > MAX_DURATION
    )


def _best_cut(words: list[Word]) -> int:
    """Index to split before: balanced halves, preferring punctuation and pauses."""
    total = text_len(_join(words))
    best, best_score = len(words) // 2, float("inf")
    left = 0.0
    for i in range(1, len(words)):
        left += text_len(words[i - 1].text)
        balance = abs(left - (total - left)) / total  # 0 = perfectly even
        bonus = 0.0
        prev = words[i - 1].text.strip()
        if prev.endswith(CLAUSE_BREAK):
            bonus += 0.35
        elif prev.endswith(LIST_BREAK):
            bonus += 0.1
        gap = words[i].start - words[i - 1].end
        bonus += min(gap, 1.0) * 0.3
        score = balance - bonus
        if score < best_score:
            best, best_score = i, score
    return best


def _split(words: list[Word]) -> list[list[Word]]:
    if not _too_long(words):
        return [words]
    cut = _best_cut(words)
    return _split(words[:cut]) + _split(words[cut:])


def regroup(words: list[Word]) -> list[Segment]:
    """Whole sentences, each short enough for one subtitle."""
    words = _unglue(words)
    groups = _merge_orphans([part for sentence in _sentences(words) for part in _split(sentence)])
    segments = []
    for group in groups:
        text = _join(group)
        if text:
            segments.append(Segment(id=len(segments) + 1, start=group[0].start, end=group[-1].end, text=text))
    return segments
