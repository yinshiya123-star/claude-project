"""Voice cloning for dubbing (optional): speak in the speakers' own voices.

edge-tts voices are other people's voices; however well they are matched,
they don't sound like the person in the video. With a SiliconFlow
(硅基流动, https://siliconflow.cn) API key, every speaker's voice is cloned
from the video itself with CosyVoice2 (FunAudioLLM/CosyVoice2-0.5B, works
across languages: an English speaker's voice can speak the Chinese dub):

1. reference: about 10 s of that speaker's clearest lines, with their
   transcript, cut from the original audio;
2. upload it as a custom voice (POST /uploads/audio/voice -> uri);
3. speak every line with that voice (POST /audio/speech);
4. delete the custom voices afterwards.
"""

from __future__ import annotations

import io
import os
import re
import wave

from .subtitles import Segment

BASE_URL = os.getenv("SILICONFLOW_BASE_URL", "https://api.siliconflow.cn/v1").rstrip("/")
MODEL = os.getenv("SILICONFLOW_TTS_MODEL", "FunAudioLLM/CosyVoice2-0.5B")
SR = 16000
REF_SECONDS = 10.0  # the recommended reference length
REF_MAX = 15.0
LINE_MIN, LINE_MAX = 1.5, 12.0  # lines usable in a reference
GAP = 0.25  # silence between lines in the reference


class CloneError(RuntimeError):
    pass


def api_key(given: str | None) -> str | None:
    return (given or "").strip() or os.getenv("SILICONFLOW_API_KEY") or None


def _client(key: str):
    import httpx

    return httpx.Client(base_url=BASE_URL, headers={"Authorization": f"Bearer {key}"}, timeout=120)


def _check(r) -> None:
    if r.status_code < 400:
        return
    try:
        detail = r.json().get("message") or r.text
    except Exception:
        detail = r.text
    if r.status_code == 401:
        raise CloneError("硅基流动 API Key 无效，请检查后重试")
    if r.status_code in (402, 403) or "balance" in str(detail).lower():
        raise CloneError(f"硅基流动账户余额不足或没有权限：{detail}")
    raise CloneError(f"硅基流动接口出错（{r.status_code}）：{detail}")


def reference(audio, segments: list[Segment], line_ids: list[int], loudness: dict[int, float]):
    """(16 kHz WAV bytes, transcript) of a speaker's clearest lines, about REF_SECONDS long,
    or None if the speaker has no usable line."""
    import numpy as np

    lines = [s for s in segments if s.id in line_ids and LINE_MIN <= s.end - s.start <= LINE_MAX and s.text.strip()]
    if not lines:  # only short lines: take the longest few anyway
        lines = sorted((s for s in segments if s.id in line_ids and s.text.strip()),
                       key=lambda s: s.end - s.start, reverse=True)[:6]
    if not lines:
        return None
    picked, total = [], 0.0
    for s in sorted(lines, key=lambda s: loudness.get(s.id, 0), reverse=True):
        if total >= REF_SECONDS or total + (s.end - s.start) > REF_MAX:
            continue
        picked.append(s)
        total += s.end - s.start
    picked.sort(key=lambda s: s.start)
    gap = np.zeros(int(GAP * SR), dtype=np.float32)
    parts = []
    for s in picked:
        parts += [audio[int(s.start * SR): int(s.end * SR)], gap]
    clip = np.concatenate(parts[:-1])
    peak = float(np.abs(clip).max() or 1.0)
    clip = clip * min(1.0, 0.9 / peak) if peak > 0.9 else clip
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((np.clip(clip, -1, 1) * 32767).astype("<i2").tobytes())
    joiner = "" if re.search(r"[぀-ヿ㐀-鿿]", "".join(s.text for s in picked)) else " "
    return buf.getvalue(), joiner.join(s.text.strip() for s in picked)


def upload(key: str, wav: bytes, text: str, name: str) -> str:
    """Create a custom voice from a reference clip; returns its uri."""
    with _client(key) as c:
        r = c.post("/uploads/audio/voice", files={"file": ("reference.wav", wav, "audio/wav")},
                   data={"model": MODEL, "customName": name, "text": text})
    _check(r)
    uri = r.json().get("uri")
    if not uri:
        raise CloneError(f"硅基流动没有返回音色：{r.text[:200]}")
    return uri


def speak(key: str, uri: str, text: str, path: str, speed: float = 1.0) -> None:
    """Write `text` spoken in the cloned voice to `path` (MP3)."""
    with _client(key) as c:
        r = c.post("/audio/speech", json={"model": MODEL, "input": text, "voice": uri,
                                          "response_format": "mp3", "speed": round(max(0.5, min(2.0, speed)), 2)})
    _check(r)
    with open(path, "wb") as f:
        f.write(r.content)


def delete(key: str, uri: str) -> None:
    try:
        with _client(key) as c:
            c.post("/audio/voice/deletions", json={"uri": uri})
    except Exception:
        pass  # only clean-up
