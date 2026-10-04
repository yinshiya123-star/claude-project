"""Translate the text that appears in the video picture.

Signs, titles, slides, captions and foreign hard subtitles are read from the
frames with OCR, followed over time, translated with the subtitle pipeline,
then shown on the player or burned into the video in place of (or below) the
original text.

1. Sample a frame every `interval` seconds and OCR it (frames are shrunk to
   OCR_MAX_SIDE first for speed; boxes are scaled back).
2. Track: a line seen in consecutive samples at about the same place with
   about the same text is one event with a start and an end time.
3. Translate the events' text in order of appearance (context for the LLM).
"""

from __future__ import annotations

from difflib import SequenceMatcher
from typing import Callable

from . import image_translate
from .subtitles import _timestamp

OCR_MAX_SIDE = 1280
INTERVALS = (0.5, 1.0, 2.0)
MIN_SCORE = 0.6
SAME_TEXT = 0.75  # similarity for "the same line" between samples
SAME_PLACE = 0.3  # box overlap (IoU)


def _iou(a, b) -> float:
    ax0, ay0, ax1, ay1 = image_translate._rect(a)
    bx0, by0, bx1, by1 = image_translate._rect(b)
    ix = max(0, min(ax1, bx1) - max(ax0, bx0))
    iy = max(0, min(ay1, by1) - max(ay0, by0))
    inter = ix * iy
    union = (ax1 - ax0) * (ay1 - ay0) + (bx1 - bx0) * (by1 - by0) - inter
    return inter / union if union else 0.0


def _frames(path: str, interval: float):
    """(time, RGB array) every `interval` seconds, plus the duration and picture size."""
    import av

    from .burn import _rotate
    from .media import picture_stream

    with av.open(path) as container:
        stream = picture_stream(container)
        if stream is None:
            raise RuntimeError("这个文件没有画面，无法识别画面文字")
        stream.thread_type = "AUTO"
        duration = float(container.duration / av.time_base) if container.duration else 0.0
        next_t = 0.0
        for frame in container.decode(stream):
            if frame.time is None or frame.time + 1e-6 < next_t:
                continue
            yield frame.time, _rotate(frame.to_ndarray(format="rgb24"), getattr(frame, "rotation", 0)), duration
            next_t = frame.time + interval


def has_picture(path: str) -> bool:
    import av

    from .media import picture_stream

    try:
        with av.open(path) as container:
            return picture_stream(container) is not None
    except Exception:
        return False


def _shrink(rgb):
    import numpy as np
    from PIL import Image

    h, w = rgb.shape[:2]
    scale = min(1.0, OCR_MAX_SIDE / max(h, w))
    if scale == 1.0:
        return rgb, 1.0
    img = Image.fromarray(rgb).resize((int(w * scale), int(h * scale)))
    return np.asarray(img), scale


def scan(path: str, lang: str = "auto", interval: float = 1.0,
         on_progress: Callable[[float], None] | None = None) -> tuple[list[dict], list[int]]:
    """Text events [{"start", "end", "box", "text", "score"}] and the picture size [w, h]."""
    events: list[dict] = []
    active: list[dict] = []
    size = [0, 0]
    last_t = 0.0
    for t, rgb, duration in _frames(path, interval):
        size = [rgb.shape[1], rgb.shape[0]]
        small, scale = _shrink(rgb)
        lines = [
            {**l, "box": [[x / scale, y / scale] for x, y in l["box"]]}
            for l in image_translate.ocr(small, lang)
            if l["score"] >= MIN_SCORE and len(l["text"].strip()) >= 2
        ]
        still = []
        for line in lines:
            match = next((e for e in active if e not in still
                          and SequenceMatcher(None, e["text"], line["text"]).ratio() >= SAME_TEXT
                          and _iou(e["box"], line["box"]) >= SAME_PLACE), None)
            if match:
                match["end"] = t + interval
                if line["score"] > match["score"]:  # keep the clearest reading
                    match.update(text=line["text"], box=line["box"], score=line["score"])
                still.append(match)
            else:
                event = {"start": t, "end": t + interval, "box": line["box"], "text": line["text"], "score": line["score"]}
                events.append(event)
                still.append(event)
        active = still
        last_t = t
        if on_progress and duration:
            on_progress(min(1.0, t / duration))
    end = max(last_t + interval, 0)
    for e in events:
        e["end"] = round(min(e["end"], end), 3)
        e["start"] = round(e["start"], 3)
        e["box"] = [[round(x, 1), round(y, 1)] for x, y in e["box"]]
    events.sort(key=lambda e: (e["start"], min(p[1] for p in e["box"])))
    for i, e in enumerate(events, 1):
        e["id"] = i
    if on_progress:
        on_progress(1.0)
    return events, size


def translate_events(events: list[dict], target: str, api_key: str | None, ocr_lang: str = "auto",
                     custom_terms: list[dict] | None = None, on_progress=None) -> dict:
    """Fill zh / en of every event (same targets as image translation)."""
    if not events:
        return {"theme": "", "terms": [], "language": None}
    return image_translate.translate_lines(events, target, api_key, ocr_lang, custom_terms, on_progress)


def to_srt(events: list[dict], mode: str) -> str:
    """The on-screen text as a subtitle file; {\\an8} asks players to show it at the top,
    away from the dialogue subtitles."""
    blocks = []
    for i, e in enumerate(sorted(events, key=lambda e: e["start"]), 1):
        if mode in ("zh", "en"):
            lines = [e.get(mode) or e["text"]]
        else:
            lines = list(dict.fromkeys(t for t in (e["text"], e.get("zh"), e.get("en")) if t))
        blocks.append(f"{i}\n{_timestamp(e['start'], ',')} --> {_timestamp(e['end'], ',')}\n{{\\an8}}" + "\n".join(lines))
    return "\n\n".join(blocks) + "\n" if blocks else ""


class ScreenOverlay:
    """Draws translated on-screen text onto video frames while burning.

    Each event's overlay is built once, from the first frame it is shown on
    (that frame gives the background and text colours), then blended in."""

    def __init__(self, events: list[dict], mode: str, frame_size: list[int], width: int, height: int):
        from .burn import find_font

        sx = width / frame_size[0] if frame_size and frame_size[0] else 1.0
        sy = height / frame_size[1] if frame_size and frame_size[1] else 1.0
        self.events = [
            {**e, "box": [[x * sx, y * sy] for x, y in e["box"]]}
            for e in sorted(events, key=lambda e: e["start"])
        ]
        self.mode = mode
        self.font = find_font()
        self.patches: dict[int, object] = {}

    def draw(self, rgb, t: float) -> None:
        for event in self.events:
            if event["start"] > t:
                break
            if t >= event["end"]:
                continue
            if event["id"] not in self.patches:
                self.patches[event["id"]] = image_translate.make_patch(rgb, event, self.mode, self.font)
            patch = self.patches[event["id"]]
            if patch is not None:
                image_translate.composite(rgb, *patch)
