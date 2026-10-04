"""AI dubbing: speak the translated subtitles and mix them into the video.

The steps follow VideoLingo's dubbing pipeline (https://github.com/Huanshere/VideoLingo,
Apache-2.0): synthesise every subtitle line, fit each clip into its subtitle's
time slot, place the clips on a timeline and merge them with the video.

- Speech comes from edge-tts (Microsoft Edge's online neural voices: free, no
  key, natural Chinese and English voices). Voices are matched to the people
  in the video automatically (voices.py): speakers are told apart by
  voiceprint, each gets the closest voice shifted to their pitch and speed,
  and every line follows the original line's pitch and loudness.
- With a SiliconFlow key the speakers' own voices are cloned instead (clone.py).
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
TARGET_RMS = 0.1  # loudness of a dubbed line of average original loudness
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


def synthesize(text: str, voice: str, path: str, rate: int = 0, pitch: str = "+0Hz") -> None:
    """Write speech for `text` to `path` (MP3) with edge-tts; rate in percent, pitch like "+10Hz"."""
    import edge_tts

    async def run():
        proxy = os.getenv("HTTPS_PROXY") or os.getenv("https_proxy") or None
        await edge_tts.Communicate(text, voice, rate=f"{rate:+d}%", pitch=pitch, proxy=proxy).save(path)

    try:
        asyncio.run(run())
    except Exception as e:
        raise DubError(f"语音合成失败（需要能访问微软 Edge 语音服务）：{e}") from e


def _speak(text: str, voice: dict, path: str, rate: int) -> None:
    """One line with the line's voice settings: a cloned voice or an edge-tts voice."""
    clone = voice.get("clone")
    if clone:
        from . import clone as cloning

        try:
            cloning.speak(clone["key"], clone["uri"], text, path, 1 + rate / 100)
        except cloning.CloneError as e:
            raise DubError(str(e)) from e
        except Exception as e:
            raise DubError(f"声音克隆合成失败（需要能访问硅基流动）：{e}") from e
    else:
        synthesize(text, voice["voice"], path, rate, voice["pitch"])


def _load(path: str):
    from .transcriber import decode_audio

    return decode_audio(path, RATE)


def _clip_for(seg: Segment, text: str, voice: dict, slot: float, tmp: str, gain: float = 1.0):
    """Speech for one line, fitted into `slot` seconds; `voice` = {"voice", "pitch", "rate"
    (the speaker's speed in percent), optional "clone"}."""
    import numpy as np

    path = os.path.join(tmp, f"{seg.id}.mp3")
    base = voice.get("rate", 0)
    _speak(text, voice, path, base)
    audio = _load(path)
    length = len(audio) / RATE
    if length > slot * 1.03:
        factor = (1 + base / 100) * (length / slot) * 1.05  # speed that fits, relative to normal
        _speak(text, voice, path, min(MAX_SPEEDUP, math.ceil((factor - 1) * 100)))
        audio = _load(path)
    rms = float(np.sqrt(np.mean(audio ** 2))) if len(audio) else 0.0
    if rms > 1e-4:
        audio = audio * (TARGET_RMS * gain / rms)  # follow the original line's loudness
    limit = int(slot * RATE)
    if len(audio) > limit:
        audio = audio[:limit].copy()
        fade = min(int(FADE * RATE), limit)
        if fade:
            audio[-fade:] *= np.linspace(1, 0, fade, dtype=np.float32)
    return audio


def voice_track(segments: list[Segment], lang: str, voices: dict[int, dict], duration: float,
                on_progress: Callable[[float], None] | None = None, gains: dict[int, float] | None = None):
    """Mono float32 timeline (RATE Hz) with every line spoken at its start time,
    line `id` in voice `voices[id]`."""
    import numpy as np

    segs = sorted((s for s in segments if _text(s, lang)), key=lambda s: s.start)
    end = max([duration] + [s.end for s in segs])
    track = np.zeros(int(end * RATE) + 1, dtype=np.float32)
    done, lock = [0], threading.Lock()

    with tempfile.TemporaryDirectory() as tmp:
        def work(i):
            seg = segs[i]
            next_start = segs[i + 1].start if i + 1 < len(segs) else end
            slot = max(0.3, next_start - seg.start - SLOT_MARGIN)
            clip = _clip_for(seg, _text(seg, lang), voices[seg.id], slot, tmp, (gains or {}).get(seg.id, 1.0))
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


def match_voices(src: str, segments: list[Segment], lang: str, clone_key: str | None = None,
                 cloned: list | None = None) -> tuple[dict[int, dict], dict[int, float], list[dict]]:
    """Voice settings per line from the speakers in the original audio, loudness gain
    per line, and a summary of who got which voice. With `clone_key` every speaker's
    own voice is cloned; the created voices are appended to `cloned` as (key, uri)."""
    from . import voices
    from .transcriber import decode_audio

    try:
        audio = decode_audio(src, voices.SR)
    except RuntimeError:  # no sound track: one default voice
        audio = None
    if audio is None or not len(audio):
        if clone_key:
            raise DubError("原视频没有声音，无法克隆说话人的声音")
        found = {"speakers": [{"id": 0, "f0": None, "gender": "female", "lines": len(segments), "speed": None}],
                 "labels": {s.id: 0 for s in segments}, "loudness": {}, "pitch": {}, "speed": {}}
    else:
        found = voices.analyze_speakers(audio, segments)
    chosen = voices.auto_voices(found["speakers"], lang)
    per_line = voices.line_voices(found, chosen)
    loud = found["loudness"]
    levels = sorted(v for v in loud.values() if v > 1e-4)
    median = levels[len(levels) // 2] if levels else 0
    gains = {sid: min(1.6, max(0.6, v / median)) for sid, v in loud.items() if median and v > 1e-4}
    summary = voices.describe(found["speakers"], chosen)
    if clone_key:
        from . import clone

        for n, spk in enumerate(found["speakers"]):
            ids = [sid for sid, label in found["labels"].items() if label == spk["id"]]
            ref = clone.reference(audio, segments, ids, loud)
            if ref is None:
                continue  # nothing to clone from: keep the matched edge voice
            try:
                uri = clone.upload(clone_key, ref[0], ref[1], f"dub-speaker-{n + 1}")
            except clone.CloneError as e:
                raise DubError(str(e)) from e
            except Exception as e:
                raise DubError(f"上传参考音频失败（需要能访问硅基流动）：{e}") from e
            if cloned is not None:
                cloned.append((clone_key, uri))
            for sid in ids:
                per_line[sid] = {**per_line[sid], "clone": {"key": clone_key, "uri": uri}}
            summary[n]["voice"] = f"克隆原声（参考 {len(ref[1])} 字）"
    return per_line, gains, summary


def dub(src: str, dst_stem: Path, segments: list[Segment], lang: str, voice: str | None = None,
        bg_volume: float = 0.2, burn_mode: str | None = None,
        on_progress: Callable[[float], None] | None = None, clone_key: str | None = None) -> tuple[Path, list[dict]]:
    """Dub `src`; returns (output file, speaker summary). The output is MP4 for videos or
    burned output, MP3 otherwise. Voices are matched to the speakers automatically unless
    `voice` forces one voice for every line; with `clone_key` (SiliconFlow) the speakers'
    own voices are cloned.

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
    cloned: list = []
    try:
        if voice:
            per_line, gains = {s.id: {"voice": voice, "pitch": "+0Hz", "rate": 0} for s in segments}, {}
            summary = [{"speaker": "全部台词", "voice": voice}]
        else:
            per_line, gains, summary = match_voices(src, segments, lang, clone_key, cloned)
        speech = voice_track(segments, lang, per_line, duration, step(0.0, 0.7), gains)
    finally:
        from . import clone

        for key, uri in cloned:
            clone.delete(key, uri)
    if has_audio and bg_volume > 0:
        original = _load(src) * float(bg_volume)
        mixed = np.zeros(max(len(speech), len(original)), dtype=np.float32)
        mixed[: len(original)] += original
        mixed[: len(speech)] += speech
    else:
        mixed = speech
    if duration:
        mixed = mixed[: int(duration * RATE) + 1]  # the sound ends with the video
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
    return out, summary
