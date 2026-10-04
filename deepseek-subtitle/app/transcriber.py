"""Speech-to-text with faster-whisper.

DeepSeek's API is text-only, so audio is transcribed locally first and the
resulting timed segments are handed to DeepSeek for translation.
"""

from __future__ import annotations

import logging
import os
import re
import threading
from typing import Callable

from .segmenter import Word, regroup
from .subtitles import Segment

log = logging.getLogger(__name__)

_models: dict[str, object] = {}
_model_lock = threading.Lock()
_cuda_failed = False

# Whisper often writes Mandarin in traditional characters without punctuation;
# a simplified, punctuated prompt sentence steers it towards that style.
ZH_INITIAL_PROMPT = "以下是普通话的句子，使用简体中文，并带有标点符号。"


# Choices offered on the web page: speed versus accuracy.
MODELS = ("small", "medium", "large-v3-turbo")
MODEL_SIZES = {"small": "460 MB", "medium": "1.5 GB", "large-v3-turbo": "1.6 GB"}


def model_cached(name: str | None = None) -> bool:
    """Whether the model is already downloaded (else the first job downloads it)."""
    try:
        from faster_whisper.utils import download_model

        download_model(name or os.getenv("WHISPER_MODEL", "small"), local_files_only=True)
        return True
    except Exception:
        return False


def _get_model(device: str, name: str | None = None):
    name = name or os.getenv("WHISPER_MODEL", "small")
    with _model_lock:
        if (device, name) not in _models:
            from faster_whisper import WhisperModel

            compute_type = os.getenv("WHISPER_COMPUTE_TYPE") or ("int8" if device == "cpu" else "default")
            _models[(device, name)] = WhisperModel(name, device=device, compute_type=compute_type)
        return _models[(device, name)]


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
    model: str | None = None,
    hotwords: list[str] | None = None,
) -> tuple[list[Segment], str]:
    """Transcribe an audio/video file. Returns (segments, detected_language).

    `hotwords` (e.g. names from the user's glossary) bias recognition towards
    those spellings.
    """
    global _cuda_failed
    audio = decode_audio(path)
    for device in _devices():
        try:
            return _transcribe(_get_model(device, model), audio, language, on_progress, hotwords)
        except Exception:
            if device != "cuda":
                raise
            # e.g. "Library cublas64_12.dll is not found": GPU libraries missing.
            log.warning("GPU transcription failed, falling back to CPU", exc_info=True)
            _cuda_failed = True
    raise AssertionError("unreachable")  # the CPU attempt either returns or raises


# Text Whisper is known to invent over silence, music and credits (it learnt it
# from subtitle files), and our own prompt echoed back: never real speech ...
HALLUCINATIONS = re.compile(
    r"请不吝点赞|明镜与点点|字幕由.{0,20}提供|Amara\.org|优优独播|中文字幕志愿者|"
    r"subtitles by the|以下是普通话的句子|使用简体中文，并带有标点",
    re.IGNORECASE,
)
# ... and phrases that people do say, but Whisper also invents: dropped only when unsure
SUSPICIOUS = re.compile(
    r"点赞.{0,6}订阅|订阅.{0,6}(转发|打赏)|感谢(您的)?(收看|观看)|谢谢(大家)?(收看|观看)|"
    r"thanks? (you )?(so much )?for watching|please subscribe|like and subscribe|"
    r"ご視聴ありがとうございました|チャンネル登録|시청해 ?주셔서 감사합니다|구독과 좋아요",
    re.IGNORECASE,
)
MAX_REPEATS = 2  # the same line more often than this in a row is a loop


def is_hallucination(text: str, no_speech_prob: float = 0.0, avg_logprob: float = 0.0,
                     compression_ratio: float = 1.0) -> bool:
    """Whether Whisper most likely made this segment up."""
    if HALLUCINATIONS.search(text):
        return True
    if SUSPICIOUS.search(text) and (no_speech_prob > 0.2 or avg_logprob < -0.6):
        return True
    if no_speech_prob > 0.6 and avg_logprob < -0.5:  # probably not speech, and unsure about the words
        return True
    return compression_ratio > 2.4 or avg_logprob < -1.2  # repetitive gibberish / a wild guess


def _transcribe(model, audio, language, on_progress, hotwords=None) -> tuple[list[Segment], str]:
    seg_iter, info = model.transcribe(
        audio,
        language=language or None,
        initial_prompt=ZH_INITIAL_PROMPT if language == "zh" else None,
        hotwords=" ".join(hotwords) if hotwords else None,
        # Don't feed the previous window's text back in: stops one mistake from
        # spreading and the repetition loops Whisper is known for.
        condition_on_previous_text=False,
        # Shorter silences split speech, so timestamps hug the words more tightly.
        # Short noises are not speech; the padding keeps first / last syllables.
        vad_parameters={"min_silence_duration_ms": 700, "min_speech_duration_ms": 250, "speech_pad_ms": 300},
        # Auto mode: detect the language once, from several stretches of speech.
        # (Detecting it per 30 s window, multilingual=True, misjudges short windows
        # and then writes or translates text that was never said.)
        language_detection_segments=4,
        # Skip text Whisper writes over long silences (needs word timestamps).
        hallucination_silence_threshold=2.0,
        # Word timings let segmenter.regroup() rebuild whole sentences.
        word_timestamps=True,
        vad_filter=True,
        beam_size=5,
        best_of=5,
    )
    duration = info.duration or 0
    words: list[Word] = []
    last, repeats = None, 0
    for s in seg_iter:
        text = s.text.strip()
        repeats = repeats + 1 if text and text == last else 0
        last = text
        if repeats >= MAX_REPEATS or is_hallucination(text, getattr(s, "no_speech_prob", 0.0),
                                                      getattr(s, "avg_logprob", 0.0), getattr(s, "compression_ratio", 1.0)):
            log.info("dropped likely hallucination at %.1fs: %s", s.start, text)
            if on_progress and duration:
                on_progress(min(1.0, s.end / duration))
            continue
        if s.words:
            words += [Word(w.start, w.end, w.word) for w in s.words if w.word.strip()]
        elif s.text.strip():
            words.append(Word(s.start, s.end, " " + s.text.strip()))
        if on_progress and duration:
            on_progress(min(1.0, s.end / duration))
    if on_progress:
        on_progress(1.0)
    return regroup(words), info.language
