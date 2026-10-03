"""Speech-to-text with faster-whisper.

DeepSeek's API is text-only, so audio is transcribed locally first and the
resulting timed segments are handed to DeepSeek for translation.
"""

from __future__ import annotations

import logging
import os
import threading
from typing import Callable

from .subtitles import Segment

log = logging.getLogger(__name__)

_models: dict[str, object] = {}
_model_lock = threading.Lock()
_cuda_failed = False

# Whisper often writes Mandarin in traditional characters without punctuation;
# a simplified, punctuated prompt sentence steers it towards that style.
ZH_INITIAL_PROMPT = "以下是普通话的句子，使用简体中文，并带有标点符号。"


def _get_model(device: str):
    with _model_lock:
        if device not in _models:
            from faster_whisper import WhisperModel

            name = os.getenv("WHISPER_MODEL", "small")
            compute_type = os.getenv("WHISPER_COMPUTE_TYPE") or ("int8" if device == "cpu" else "default")
            _models[device] = WhisperModel(name, device=device, compute_type=compute_type)
        return _models[device]


def _cuda_available() -> bool:
    try:
        import ctranslate2

        return ctranslate2.get_cuda_device_count() > 0
    except Exception:
        return False


def _devices() -> list[str]:
    """Devices to try in order. CPU is the default: running on an NVIDIA GPU also
    needs the CUDA 12 / cuDNN 9 libraries, which most machines don't have."""
    pref = os.getenv("WHISPER_DEVICE", "cpu").lower()
    if pref == "cpu" or _cuda_failed:
        return ["cpu"]
    if pref == "cuda" or _cuda_available():
        return ["cuda", "cpu"]
    return ["cpu"]


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
    global _cuda_failed
    audio = decode_audio(path)
    for device in _devices():
        try:
            return _transcribe(_get_model(device), audio, language, on_progress)
        except Exception:
            if device != "cuda":
                raise
            # e.g. "Library cublas64_12.dll is not found": GPU libraries missing.
            log.warning("GPU transcription failed, falling back to CPU", exc_info=True)
            _cuda_failed = True
    raise AssertionError("unreachable")  # the CPU attempt either returns or raises


def _transcribe(model, audio, language, on_progress) -> tuple[list[Segment], str]:
    seg_iter, info = model.transcribe(
        audio,
        language=language or None,
        initial_prompt=ZH_INITIAL_PROMPT if language == "zh" else None,
        # Auto mode: detect the language per segment, so mixed-language audio works.
        multilingual=language is None,
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
