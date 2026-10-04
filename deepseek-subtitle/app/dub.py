"""AI dubbing: speak the translated subtitles and mix them into the video.

The steps follow VideoLingo's dubbing pipeline (https://github.com/Huanshere/VideoLingo,
Apache-2.0): synthesise every subtitle line, fit each clip into its subtitle's
time slot, place the clips on a timeline and merge them with the video.

- Speech comes from edge-tts (Microsoft Edge's online neural voices: free, no
  key, natural Chinese and English voices). Voices are matched to the people
  in the video automatically (voices.py): speakers are told apart by
  voiceprint, each gets the closest voice shifted to their pitch and speed,
  and every line follows the original line's pitch and loudness.
- By default the speakers' own voices are cloned instead, free and offline,
  with the open-source ZipVoice model (clone.py); edge-tts voices are the
  faster alternative.
- A clip longer than its slot is synthesised again with a faster speaking rate
  (up to +60 %); whatever still doesn't fit is cut with a short fade-out.
- Lines of one speaker that follow each other closely are spoken in one go
  (natural rhythm, no pause in the middle of a sentence); the silence TTS
  voices add around speech is cut.
- The original sound is muted while someone speaks and stays at full volume
  in between (music, ambience); how much of it stays under speech is adjustable.
- Videos keep their picture untouched (stream copy, fast); audio-only files
  give an MP3; optionally the subtitles are burned in as well.
"""

from __future__ import annotations

import asyncio
import math
import os
import re
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
MERGE_GAP = 0.5  # lines of one speaker closer than this are spoken in one go
MAX_UTTERANCE = 15.0  # seconds
DUCK_RAMP = 0.12  # seconds to fade the original voice out / in
DUCK_PAD = (0.1, 0.15)  # seconds of original speech muted before / after each subtitle
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
            cloning.speak(clone, text, path, 1 + rate / 100)
        except cloning.CloneCrash as e:
            # the synthesis process died: this line gets the speaker's matched edge voice
            voice["fell_back"] = str(e)
            synthesize(text, voice["voice"], path, rate, voice["pitch"])
        except cloning.CloneError as e:
            raise DubError(str(e)) from e
        except Exception as e:
            raise DubError(f"声音克隆合成失败：{e}") from e
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
    audio = _trim(_load(path))
    length = len(audio) / RATE
    if length > slot * 1.03:
        factor = (1 + base / 100) * (length / slot) * 1.05  # speed that fits, relative to normal
        _speak(text, voice, path, min(MAX_SPEEDUP, math.ceil((factor - 1) * 100)))
        audio = _trim(_load(path))
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


def _same_voice(a: dict, b: dict) -> bool:
    return (a.get("voice"), a.get("speaker"), id(a.get("clone"))) == (b.get("voice"), b.get("speaker"), id(b.get("clone")))


def _join(texts: list[str], lang: str) -> str:
    """Lines of one utterance as one text; a line without end punctuation gets a comma
    so the voice pauses briefly instead of running the parts together."""
    out = ""
    for t in texts:
        if out:
            if not re.search(r"[，。！？；、,.!?;:：…—]$", out):
                out += "，" if lang == "zh" else ","
            out += "" if lang == "zh" else " "
        out += t
    return out


def utterances(segments: list[Segment], lang: str, voices: dict[int, dict],
               gains: dict[int, float] | None = None) -> list[dict]:
    """Subtitle lines grouped into what is spoken in one breath: consecutive lines of
    the same speaker with less than MERGE_GAP between them. One sentence split over
    several subtitles is then synthesised once, with natural rhythm and intonation,
    instead of in pieces with a pause after each."""
    segs = sorted((s for s in segments if _text(s, lang)), key=lambda s: s.start)
    groups: list[list[Segment]] = []
    for seg in segs:
        last = groups[-1] if groups else None
        if (last and seg.start - last[-1].end < MERGE_GAP and seg.end - last[0].start <= MAX_UTTERANCE
                and _same_voice(voices[seg.id], voices[last[0].id])):
            last.append(seg)
        else:
            groups.append([seg])
    out = []
    for n, group in enumerate(groups):
        gs = [(gains or {}).get(s.id, 1.0) for s in group]
        out.append({"id": n, "start": group[0].start, "end": group[-1].end, "voice": voices[group[0].id],
                    "text": _join([_text(s, lang) for s in group], lang), "gain": sum(gs) / len(gs),
                    "lines": [s.id for s in group]})
    return out


def _trim(audio):
    """Cut the silence TTS voices put before and after the speech."""
    import numpy as np

    hop = RATE // 100
    if len(audio) < hop * 3:
        return audio
    frames = len(audio) // hop
    rms = np.sqrt(np.mean(audio[: frames * hop].reshape(frames, hop) ** 2, axis=1))
    loud = np.nonzero(rms > max(0.003, float(rms.max()) * 0.02))[0]
    if not len(loud):
        return audio
    pad = 3  # 30 ms
    return audio[max(0, loud[0] - pad) * hop: min(frames, loud[-1] + 1 + pad) * hop]


def voice_track(segments: list[Segment], lang: str, voices: dict[int, dict], duration: float,
                on_progress: Callable[[float], None] | None = None, gains: dict[int, float] | None = None,
                spans: list | None = None):
    """Mono float32 timeline (RATE Hz) with every utterance spoken at its start time,
    line `id` in voice `voices[id]`. The time ranges with dubbed speech are appended
    to `spans` as (start, end) seconds."""
    import numpy as np

    parts = utterances(segments, lang, voices, gains)
    end = max([duration] + [u["end"] for u in parts])
    track = np.zeros(int(end * RATE) + 1, dtype=np.float32)
    done, lock = [0], threading.Lock()

    with tempfile.TemporaryDirectory() as tmp:
        def work(i):
            u = parts[i]
            next_start = parts[i + 1]["start"] if i + 1 < len(parts) else end
            slot = max(0.3, next_start - u["start"] - SLOT_MARGIN)
            seg = Segment(u["id"], u["start"], u["end"], u["text"])
            clip = _clip_for(seg, u["text"], u["voice"], slot, tmp, u["gain"])
            with lock:
                done[0] += 1
                if on_progress:
                    on_progress(done[0] / len(parts))
            return u, clip

        with ThreadPoolExecutor(max_workers=WORKERS) as pool:
            for u, clip in pool.map(work, range(len(parts))):
                start = int(u["start"] * RATE)
                part = clip[: len(track) - start]
                track[start : start + len(part)] += part
                if spans is not None:
                    spans.append((u["start"], u["start"] + len(part) / RATE))
    return track


def duck(original, speech_spans: list, level: float, ramp: float = DUCK_RAMP):
    """The original sound at `level` while someone speaks (the original speaker or the
    dub), at full volume elsewhere: music and ambience stay, the original voice goes."""
    import numpy as np

    env = np.ones(len(original), dtype=np.float32)
    r = max(1, int(ramp * RATE))
    for a, b in speech_spans:
        i, j = max(0, int(a * RATE)), min(len(env), int(b * RATE))
        if j <= 0 or i >= len(env):
            continue
        env[i:j] = np.minimum(env[i:j], level)
        lo = max(0, i - r)  # fade down before ...
        env[lo:i] = np.minimum(env[lo:i], np.linspace(1, level, r, dtype=np.float32)[r - (i - lo):])
        hi = min(len(env), j + r)  # ... and back up after
        env[j:hi] = np.minimum(env[j:hi], np.linspace(level, 1, r, dtype=np.float32)[: hi - j])
    return original * env


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


def match_voices(src: str, segments: list[Segment], lang: str,
                 clone: bool = False) -> tuple[dict[int, dict], dict[int, float], list[dict]]:
    """Voice settings per line from the speakers in the original audio, loudness gain
    per line, and a summary of who got which voice. With `clone` every speaker's own
    voice is cloned from the video; otherwise the closest edge-tts voice is used."""
    from . import voices
    from .transcriber import decode_audio

    try:
        audio = decode_audio(src, voices.SR)
    except RuntimeError:  # no sound track: one default voice
        audio = None
    if audio is None or not len(audio):
        if clone:
            raise DubError("原视频没有声音，无法克隆说话人的声音；请把配音方式改成「微软神经语音」")
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
    if clone:
        from . import clone as cloning

        for n, spk in enumerate(found["speakers"]):
            ids = [sid for sid, label in found["labels"].items() if label == spk["id"]]
            ref = cloning.reference(audio, segments, ids, loud)
            if ref is None:
                continue  # nothing to clone from: keep the matched edge voice
            for sid in ids:  # the cloned voice carries the speaker's own pitch and pace
                per_line[sid] = {**per_line[sid], "clone": ref, "rate": 0}
            summary[n]["voice"] = f"克隆原声（参考 {len(ref['samples']) / cloning.SR:.0f} 秒原声）"
    return per_line, gains, summary


def dub(src: str, dst_stem: Path, segments: list[Segment], lang: str, voice: str | None = None,
        bg_volume: float = 0.0, burn_mode: str | None = None,
        on_progress: Callable[[float], None] | None = None, clone: bool = False) -> tuple[Path, list[dict]]:
    """Dub `src`; returns (output file, speaker summary). The output is MP4 for videos or
    burned output, MP3 otherwise. Voices are matched to the speakers automatically unless
    `voice` forces one voice for every line; with `clone` the speakers' own voices are
    cloned (ZipVoice, offline).

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
    if voice:
        per_line, gains = {s.id: {"voice": voice, "pitch": "+0Hz", "rate": 0} for s in segments}, {}
        summary = [{"speaker": "全部台词", "voice": voice}]
    else:
        if clone:
            from . import clone as cloning

            try:
                cloning.prepare()  # first time: download the model (about 160 MB)
            except cloning.CloneError as e:
                raise DubError(f"{e}。也可以把配音方式改成「微软神经语音」") from e
        per_line, gains, summary = match_voices(src, segments, lang, clone)
    spans: list = []
    speech = voice_track(segments, lang, per_line, duration, step(0.0, 0.7), gains, spans)
    fell_back = {v["fell_back"] for v in per_line.values() if v.get("fell_back")}
    if fell_back:
        summary.append({"speaker": "⚠️ 注意", "voice": "声音克隆出错，部分句子改用了匹配的微软神经语音："
                        + "；".join(sorted(fell_back))[:300] + "（详细日志见 data/clone-worker.log）"})
    if has_audio:
        talking = [(s.start - DUCK_PAD[0], s.end + DUCK_PAD[1]) for s in segments] + spans
        original = duck(_load(src), talking, float(bg_volume))
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
