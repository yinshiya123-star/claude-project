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


def decode_audio(path: str, sampling_rate: int = 16000):
    """Decode any audio/video file to mono float32 PCM at `sampling_rate`.

    faster-whisper ships its own decoder, but it passes `metadata_errors` to
    av.open(), which PyAV 19 removed. Decoding here keeps every PyAV version working.
    """
    import av
    import numpy as np

    resampler = av.audio.resampler.AudioResampler(format="s16", layout="mono", rate=sampling_rate)
    chunks = []
    with av.open(path, mode="r") as container:
        if not container.streams.audio:
            raise RuntimeError("文件里没有音轨，无法识别语音")
        frames = container.decode(audio=0)
        while True:
            try:
                frame = next(frames)
            except StopIteration:
                break
            except av.error.InvalidDataError:
                continue  # skip corrupt frames instead of failing the whole file
            for out in resampler.resample(frame):
                chunks.append(out.to_ndarray())
        for out in resampler.resample(None):
            chunks.append(out.to_ndarray())
    if not chunks:
        return np.zeros(0, dtype=np.float32)
    return np.concatenate(chunks, axis=1).reshape(-1).astype(np.float32) / 32768.0


def transcribe(
    path: str,
    language: str | None = None,
    on_progress: Callable[[float], None] | None = None,
) -> tuple[list[Segment], str]:
    """Transcribe an audio/video file. Returns (segments, detected_language)."""
    model = _get_model()
    seg_iter, info = model.transcribe(
        decode_audio(path),
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
