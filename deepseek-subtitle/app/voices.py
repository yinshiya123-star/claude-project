"""Pick dubbing voices that match the people speaking in the video.

No manual choice: every subtitle line is measured in the original audio,
- its pitch (median fundamental frequency of the voiced frames) and
- its loudness,
lines with a similar pitch are grouped into one speaker, and each speaker gets
the closest edge-tts voice: male or female from the pitch, deeper or brighter
voices for lower or higher speakers, a small pitch shift towards the speaker's
own pitch, and the dub of each line follows the original loudness.

This is a lightweight stand-in for speaker diarisation: two speakers with a
very similar pitch end up with the same voice.
"""

from __future__ import annotations

import math

from .subtitles import Segment

SR = 16000
FRAME = 1024  # 64 ms
HOP = 512
F0_MIN, F0_MAX = 60.0, 400.0
VOICED = 0.35  # normalised autocorrelation needed to call a frame voiced
SPLIT_SEMITONES = 3.0  # pitch gap between two speakers
MAX_SPEAKERS = 4
MALE_BELOW = 165.0  # Hz
MAX_SHIFT = 30  # Hz of edge-tts pitch shift

# (voice, typical pitch in Hz, description), from deep to bright
EDGE_VOICES = {
    "zh": {
        "male": [("zh-CN-YunjianNeural", 110, "云健（男声，浑厚）"), ("zh-CN-YunyangNeural", 120, "云扬（男声，沉稳）"),
                 ("zh-CN-YunxiNeural", 135, "云希（男声，阳光）"), ("zh-CN-YunxiaNeural", 200, "云夏（少年音）")],
        "female": [("zh-CN-XiaoxiaoNeural", 210, "晓晓（女声，温暖）"), ("zh-CN-XiaoyiNeural", 240, "晓伊（女声，活泼）")],
    },
    "en": {
        "male": [("en-US-ChristopherNeural", 105, "Christopher（男声，浑厚）"), ("en-US-GuyNeural", 120, "Guy（男声）"),
                 ("en-US-EricNeural", 130, "Eric（男声，明亮）"), ("en-US-RogerNeural", 140, "Roger（男声）")],
        "female": [("en-US-MichelleNeural", 195, "Michelle（女声，沉稳）"), ("en-US-AriaNeural", 210, "Aria（女声）"),
                   ("en-US-JennyNeural", 220, "Jenny（女声，明亮）"), ("en-US-AnaNeural", 280, "Ana（童声）")],
    },
}


def _part(audio, seg: Segment):
    return audio[int(seg.start * SR): int(seg.end * SR)]


def pitch(samples) -> float | None:
    """Median fundamental frequency (Hz) of the voiced frames, None if unvoiced."""
    import numpy as np

    if len(samples) < FRAME:
        return None
    energy_floor = max(1e-4, float(np.sqrt(np.mean(samples ** 2))) * 0.3)
    window = np.hanning(FRAME).astype(np.float32)
    lo, hi = int(SR / F0_MAX), int(SR / F0_MIN)
    values = []
    for start in range(0, len(samples) - FRAME, HOP):
        frame = samples[start: start + FRAME] * window
        if float(np.sqrt(np.mean(frame ** 2))) < energy_floor:
            continue
        spectrum = np.fft.rfft(frame, 2 * FRAME)
        ac = np.fft.irfft(spectrum * np.conj(spectrum))[:FRAME]
        if ac[0] <= 0:
            continue
        ac = ac / ac[0]
        window_ac = ac[lo:hi]
        best = float(window_ac.max())
        if best < VOICED:
            continue
        # The first peak close to the best one is the period; later peaks are its
        # multiples (picking those would hear a voice an octave too low).
        peaks = [i for i in range(1, len(window_ac) - 1)
                 if window_ac[i] >= 0.9 * best and window_ac[i] >= window_ac[i - 1] and window_ac[i] >= window_ac[i + 1]]
        lag = lo + (peaks[0] if peaks else int(np.argmax(window_ac)))
        values.append(SR / lag)
    return float(np.median(values)) if values else None


def loudness(samples) -> float:
    import numpy as np

    return float(np.sqrt(np.mean(samples ** 2))) if len(samples) else 0.0


def _semitones(f0: float) -> float:
    return 12 * math.log2(f0 / 100.0)


def analyze_speakers(audio, segments: list[Segment]) -> dict:
    """{"labels": {segment id: speaker id}, "speakers": [{"id", "f0", "gender", "lines"}],
    "loudness": {segment id: rms}} from 16 kHz mono audio."""
    f0s = {s.id: pitch(_part(audio, s)) for s in segments}
    loud = {s.id: loudness(_part(audio, s)) for s in segments}
    voiced = sorted((f, sid) for sid, f in f0s.items() if f)

    # 1-D clustering on the semitone scale: a big gap between neighbours starts a new speaker
    groups: list[list[tuple[float, int]]] = []
    for f, sid in voiced:
        if groups and _semitones(f) - _semitones(groups[-1][-1][0]) < SPLIT_SEMITONES:
            groups[-1].append((f, sid))
        else:
            groups.append([(f, sid)])
    while len(groups) > MAX_SPEAKERS:  # merge the two closest groups
        gaps = [_semitones(groups[i + 1][0][0]) - _semitones(groups[i][-1][0]) for i in range(len(groups) - 1)]
        i = gaps.index(min(gaps))
        groups[i: i + 2] = [groups[i] + groups[i + 1]]

    labels: dict[int, int] = {}
    speakers = []
    for n, group in enumerate(groups):
        median = sorted(f for f, _ in group)[len(group) // 2]
        speakers.append({"id": n, "f0": round(median, 1), "gender": "male" if median < MALE_BELOW else "female",
                         "lines": len(group)})
        for _, sid in group:
            labels[sid] = n
    if not speakers:  # nothing voiced (music, whispers): one neutral speaker
        speakers.append({"id": 0, "f0": None, "gender": "female", "lines": 0})
    # unvoiced lines belong to the speaker of the nearest line before them
    last = speakers[0]["id"]
    for s in sorted(segments, key=lambda s: s.start):
        last = labels.setdefault(s.id, last)
    return {"labels": labels, "speakers": speakers, "loudness": loud}


def auto_voices(speakers: list[dict], lang: str) -> dict[int, dict]:
    """speaker id -> {"voice", "pitch" (edge-tts "+NHz"), "label"}: closest voice per speaker,
    different voices for different speakers of the same gender."""
    pool = EDGE_VOICES[lang]
    chosen: dict[int, dict] = {}
    for gender in ("male", "female"):
        group = sorted((s for s in speakers if s["gender"] == gender), key=lambda s: s["f0"] or 0)
        voices = pool[gender]
        used: set[str] = set()
        for s in group:
            f0 = s["f0"] or voices[0][1]
            free = [v for v in voices if v[0] not in used] or voices
            voice, base, label = min(free, key=lambda v: abs(_semitones(v[1]) - _semitones(f0)))
            used.add(voice)
            shift = max(-MAX_SHIFT, min(MAX_SHIFT, round(f0 - base))) if s["f0"] else 0
            chosen[s["id"]] = {"voice": voice, "pitch": f"{shift:+d}Hz", "label": label}
    return chosen


def describe(speakers: list[dict], voices: dict[int, dict]) -> list[dict]:
    """Human-readable summary for the web page."""
    out = []
    for s in speakers:
        who = "男声" if s["gender"] == "male" else "女声"
        out.append({
            "speaker": f"说话人 {s['id'] + 1}（{who}" + (f"，约 {int(s['f0'])} Hz" if s["f0"] else "") + f"，{s['lines']} 句）",
            "voice": voices[s["id"]]["label"],
        })
    return out
