"""Regroup Whisper's word timestamps into subtitle-sized sentences.

Whisper cuts segments at arbitrary points, often mid-sentence. Following the
approach of VideoLingo (https://github.com/Huanshere/VideoLingo, Apache-2.0),
words are first joined into whole sentences, then sentences that are too long
for one subtitle are split at the most natural point near the middle: after a
comma or at a pause. VideoLingo uses spaCy and an LLM for this; here word
timings and punctuation do the job, so no extra models are needed.
"""

from __future__ import annotations

from dataclasses import dataclass

from .subtitles import Segment

SENTENCE_END = tuple(".?!。？！…")
CLAUSE_BREAK = tuple(",;:，；：")
LIST_BREAK = tuple("、")  # enumeration comma: a weaker split point than a real comma
PAUSE = 1.2  # seconds of silence that end a sentence
MAX_LEN = 60  # weighted length (see text_len) of one subtitle
MAX_DURATION = 8.0  # seconds


@dataclass
class Word:
    start: float
    end: float
    text: str  # as Whisper gives it, Latin words keep their leading space


def text_len(text: str) -> float:
    """Display width: CJK characters count roughly double (VideoLingo's calc_len idea)."""
    width = 0.0
    for ch in text:
        code = ord(ch)
        if 0x3000 <= code <= 0x9FFF or 0xAC00 <= code <= 0xD7AF or 0xFF00 <= code <= 0xFFEF:
            width += 1.75
        else:
            width += 1
    return width


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
    groups = [part for sentence in _sentences(words) for part in _split(sentence)]
    segments = []
    for group in groups:
        text = _join(group)
        if text:
            segments.append(Segment(id=len(segments) + 1, start=group[0].start, end=group[-1].end, text=text))
    return segments
