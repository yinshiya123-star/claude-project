"""Speech-to-text with faster-whisper.

DeepSeek's API is text-only, so audio is transcribed locally first and the
resulting timed segments are handed to DeepSeek for translation.
"""

from __future__ import annotations

import os
import threading
from typing import Callable

from .subtitles import Segment

_model = None
_model_lock = threading.Lock()


def _get_model():
    global _model
    with _model_lock:
        if _model is None:
            from faster_whisper import WhisperModel

            name = os.getenv("WHISPER_MODEL", "small")
            device = os.getenv("WHISPER_DEVICE", "auto")
            compute_type = os.getenv("WHISPER_COMPUTE_TYPE", "default")
            if compute_type == "default":
                compute_type = "int8" if device == "cpu" else "default"
            _model = WhisperModel(name, device=device, compute_type=compute_type)
        return _model


def transcribe(
    path: str,
    language: str | None = None,
    on_progress: Callable[[float], None] | None = None,
) -> tuple[list[Segment], str]:
    """Transcribe an audio/video file. Returns (segments, detected_language)."""
    model = _get_model()
    seg_iter, info = model.transcribe(
        path,
        language=language or None,
        vad_filter=True,
        beam_size=5,
    )
    duration = info.duration or 0
    segments: list[Segment] = []
    for s in seg_iter:
        text = s.text.strip()
        if text:
            segments.append(Segment(id=len(segments) + 1, start=s.start, end=s.end, text=text))
        if on_progress and duration:
            on_progress(min(1.0, s.end / duration))
    if on_progress:
        on_progress(1.0)
    return segments, info.language
