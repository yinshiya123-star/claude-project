"""AI dubbing: speak the translated subtitles and mix them into the video.

The steps follow VideoLingo's dubbing pipeline (https://github.com/Huanshere/VideoLingo,
Apache-2.0): synthesise every subtitle line, fit each clip into its subtitle's
time slot, place the clips on a timeline and merge them with the video.

- Speech comes from edge-tts (Microsoft Edge's online neural voices: free, no
  key, natural Chinese and English voices).
- A clip longer than its slot is synthesised again with a faster speaking rate
  (up to +60 %); whatever still doesn't fit is cut with a short fade-out.
- The original sound can stay underneath at a chosen volume.
- Videos keep their picture untouched (stream copy, fast); audio-only files
  give an MP3; optionally the subtitles are burned in as well.
"""

from __future__ import annotations

import asyncio
import math
import os
import tempfile
import threading
import wave
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Callable

from .subtitles import Segment

RATE = 24000  # edge-tts voices are 24 kHz
MAX_SPEEDUP = 60  # percent
SLOT_MARGIN = 0.05  # seconds kept free before the next line
FADE = 0.08  # seconds
WORKERS = 4

VOICES = {
    "zh": [
        ("zh-CN-XiaoxiaoNeural", "晓晓（女声，温暖）"),
        ("zh-CN-YunxiNeural", "云希（男声，阳光）"),
        ("zh-CN-XiaoyiNeural", "晓伊（女声，活泼）"),
        ("zh-CN-YunjianNeural", "云健（男声，激情）"),
        ("zh-CN-YunyangNeural", "云扬（男声，新闻播报）"),
    ],
    "en": [
        ("en-US-AriaNeural", "Aria（女声，美式）"),
        ("en-US-GuyNeural", "Guy（男声，美式）"),
        ("en-US-JennyNeural", "Jenny（女声，美式）"),
        ("en-GB-SoniaNeural", "Sonia（女声，英式）"),
        ("en-GB-RyanNeural", "Ryan（男声，英式）"),
    ],
}


class DubError(RuntimeError):
    pass


def synthesize(text: str, voice: str, path: str, rate: int = 0) -> None:
    """Write speech for `text` to `path` (MP3) with edge-tts; rate in percent."""
    import edge_tts

    async def run():
        proxy = os.getenv("HTTPS_PROXY") or os.getenv("https_proxy") or None
        await edge_tts.Communicate(text, voice, rate=f"{rate:+d}%", proxy=proxy).save(path)

    try:
        asyncio.run(run())
    except Exception as e:
        raise DubError(f"语音合成失败（需要能访问微软 Edge 语音服务）：{e}") from e


def _load(path: str):
    from .transcriber import decode_audio

    return decode_audio(path, RATE)


def _clip_for(seg: Segment, text: str, voice: str, slot: float, tmp: str):
    """Speech for one line, fitted into `slot` seconds."""
    import numpy as np

    path = os.path.join(tmp, f"{seg.id}.mp3")
    synthesize(text, voice, path)
    audio = _load(path)
    length = len(audio) / RATE
    if length > slot * 1.03:
        rate = min(MAX_SPEEDUP, math.ceil((length / slot - 1) * 100) + 5)
        synthesize(text, voice, path, rate)
        audio = _load(path)
    limit = int(slot * RATE)
    if len(audio) > limit:
        audio = audio[:limit].copy()
        fade = min(int(FADE * RATE), limit)
        if fade:
            audio[-fade:] *= np.linspace(1, 0, fade, dtype=np.float32)
    return audio


def voice_track(segments: list[Segment], lang: str, voice: str, duration: float,
                on_progress: Callable[[float], None] | None = None):
    """Mono float32 timeline (RATE Hz) with every line spoken at its start time."""
    import numpy as np

    segs = sorted((s for s in segments if _text(s, lang)), key=lambda s: s.start)
    end = max([duration] + [s.end for s in segs])
    track = np.zeros(int(end * RATE) + RATE, dtype=np.float32)
    done, lock = [0], threading.Lock()

    with tempfile.TemporaryDirectory() as tmp:
        def work(i):
            seg = segs[i]
            next_start = segs[i + 1].start if i + 1 < len(segs) else end
            slot = max(0.3, next_start - seg.start - SLOT_MARGIN)
            clip = _clip_for(seg, _text(seg, lang), voice, slot, tmp)
            with lock:
                done[0] += 1
                if on_progress:
                    on_progress(done[0] / len(segs))
            return seg, clip

        with ThreadPoolExecutor(max_workers=WORKERS) as pool:
            for seg, clip in pool.map(work, range(len(segs))):
                start = int(seg.start * RATE)
                part = clip[: len(track) - start]
                track[start : start + len(part)] += part
    return track


def _text(seg: Segment, lang: str) -> str:
    return (seg.zh if lang == "zh" else seg.en or seg.text).strip()


def _write_wav(path: str, audio) -> None:
    import numpy as np

    pcm = (np.clip(audio, -1, 1) * 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.tobytes())


def _remux(src: str, audio_path: str, dst: str) -> None:
    """Copy the video stream as is and put the new sound next to it."""
    import av

    from .media import AudioFeeder, AudioTrack, picture_stream

    with av.open(src) as inp, av.open(dst, "w", format="mp4") as out:
        vin = picture_stream(inp)
        vout = out.add_stream_from_template(vin)
        feeder = AudioFeeder(audio_path, AudioTrack(out, "aac", 48000, 192000))
        for packet in inp.demux(vin):
            if packet.dts is None:
                continue
            t = float(packet.pts * packet.time_base) if packet.pts is not None else None
            packet.stream = vout
            out.mux(packet)
            feeder.until(t)
        feeder.close()
        feeder.track.add(None)


def dub(src: str, dst_stem: Path, segments: list[Segment], lang: str, voice: str,
        bg_volume: float = 0.2, burn_mode: str | None = None,
        on_progress: Callable[[float], None] | None = None) -> Path:
    """Dub `src`; returns the output file (MP4 for videos or burned output, MP3 otherwise).

    `dst_stem` has no extension (e.g. exports/<job>_dubbed); it is added here.
    """
    import av
    import numpy as np

    from .export import convert_to_mp3
    from .media import picture_stream

    with av.open(src) as c:
        has_video = picture_stream(c) is not None
        has_audio = bool(c.streams.audio)
        duration = float(c.duration / av.time_base) if c.duration else 0.0
    if not any(_text(s, lang) for s in segments):
        raise DubError("没有可配音的字幕文本")

    step = (lambda a, b: (lambda p: on_progress(a + (b - a) * p))) if on_progress else (lambda a, b: None)
    speech = voice_track(segments, lang, voice, duration, step(0.0, 0.7))
    if has_audio and bg_volume > 0:
        original = _load(src) * float(bg_volume)
        mixed = np.zeros(max(len(speech), len(original)), dtype=np.float32)
        mixed[: len(original)] += original
        mixed[: len(speech)] += speech
    else:
        mixed = speech
    peak = float(np.abs(mixed).max() or 1.0)
    if peak > 1.0:
        mixed /= peak  # avoid clipping where speech and original overlap

    dst_stem.parent.mkdir(parents=True, exist_ok=True)
    wav = str(dst_stem) + ".wav"
    _write_wav(wav, mixed)
    try:
        if burn_mode:
            from .burn import burn

            out = dst_stem.with_suffix(".mp4")
            burn(src, str(out), segments, burn_mode, step(0.7, 1.0), audio_source=wav)
        elif has_video:
            out = dst_stem.with_suffix(".mp4")
            try:
                _remux(src, wav, str(out))
            except Exception:
                # The source codec can't go into MP4 as is (e.g. VP8): re-encode instead.
                from .burn import burn

                burn(src, str(out), [], "zh", step(0.7, 1.0), audio_source=wav)
        else:
            out = dst_stem.with_suffix(".mp3")
            convert_to_mp3(wav, str(out))
    finally:
        os.remove(wav)
    if on_progress:
        on_progress(1.0)
    return out
