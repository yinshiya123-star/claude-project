"""Pick dubbing voices that sound like the people speaking in the video.

No manual choice. Every subtitle line is measured in the original audio:
- whose voice it is: a voiceprint (speakers.py); lines with alike voiceprints
  are one speaker, so two people with the same pitch still get different
  voices. Offline (no voiceprint model) lines are grouped by pitch instead;
- its pitch (median fundamental frequency of the voiced frames);
- its loudness;
- its speaking speed (syllables per second).

Each speaker gets the closest edge-tts voice (male or female from the pitch,
deeper or brighter voices for lower or higher speakers), shifted to the
speaker's own pitch and speed. Each line then follows the original line: a
line said higher (excited, asking) or lower than usual is dubbed higher or
lower, a fast line faster, a loud line louder.
"""

from __future__ import annotations

import math
import re

from .subtitles import Segment

SR = 16000
FRAME = 1024  # 64 ms
HOP = 512
F0_MIN, F0_MAX = 60.0, 400.0
VOICED = 0.35  # normalised autocorrelation needed to call a frame voiced
SPLIT_SEMITONES = 3.0  # pitch gap between two speakers (offline fallback)
MAX_SPEAKERS = 6
MALE_BELOW = 165.0  # Hz
MAX_SHIFT = 50  # Hz of edge-tts pitch shift for a speaker
LINE_SHIFT = 0.7  # how much of a line's own rise / fall in pitch the dub follows
MAX_LINE_SHIFT = 30  # Hz on top of the speaker's shift
TYPICAL_SPEED = 4.2  # syllables per second of an average speaker
MAX_RATE = 20  # percent faster than the voice's normal speed
MIN_RATE = -10  # percent slower: slowed-down voices drag (and line times include pauses)
RATE_FOLLOW = 0.6  # how much of the speaker's speed difference the voice follows
SPREAD_FOR_REUSE = 18  # Hz between two speakers who have to share one voice

# (voice, typical pitch in Hz, description), from deep to bright
EDGE_VOICES = {
    "zh": {
        "male": [("zh-CN-YunjianNeural", 110, "云健（男声，浑厚）"), ("zh-CN-YunyangNeural", 120, "云扬（男声，沉稳）"),
                 ("zh-TW-YunJheNeural", 125, "云哲（男声，温和）"), ("zh-CN-YunxiNeural", 135, "云希（男声，阳光）"),
                 ("zh-CN-YunxiaNeural", 200, "云夏（少年音）")],
        "female": [("zh-CN-XiaoxiaoNeural", 210, "晓晓（女声，温暖）"), ("zh-TW-HsiaoChenNeural", 220, "晓臻（女声，柔和）"),
                   ("zh-CN-XiaoyiNeural", 240, "晓伊（女声，活泼）"), ("zh-TW-HsiaoYuNeural", 255, "晓雨（女声，清亮）")],
    },
    "en": {
        "male": [("en-US-ChristopherNeural", 105, "Christopher（男声，浑厚）"), ("en-GB-RyanNeural", 112, "Ryan（男声，英式）"),
                 ("en-US-GuyNeural", 120, "Guy（男声）"), ("en-US-AndrewNeural", 125, "Andrew（男声，温和）"),
                 ("en-US-EricNeural", 130, "Eric（男声，明亮）"), ("en-US-BrianNeural", 135, "Brian（男声，随和）"),
                 ("en-US-RogerNeural", 140, "Roger（男声）")],
        "female": [("en-US-MichelleNeural", 195, "Michelle（女声，沉稳）"), ("en-GB-SoniaNeural", 205, "Sonia（女声，英式）"),
                   ("en-US-AriaNeural", 210, "Aria（女声）"), ("en-US-EmmaNeural", 215, "Emma（女声，亲切）"),
                   ("en-US-JennyNeural", 220, "Jenny（女声，明亮）"), ("en-US-AvaNeural", 230, "Ava（女声，清亮）"),
                   ("en-US-AnaNeural", 280, "Ana（童声）")],
    },
}

# Taiwanese-accent voices: only once the mainland voices are taken by other speakers
SPARE_VOICES = {"zh-TW-YunJheNeural", "zh-TW-HsiaoChenNeural", "zh-TW-HsiaoYuNeural"}


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


def syllables(text: str) -> int:
    """Rough syllable count: one per CJK character / kana / hangul, one per vowel group otherwise."""
    cjk = len(re.findall(r"[぀-ヿ㐀-鿿가-힯]", text))
    latin = re.sub(r"[぀-ヿ㐀-鿿가-힯]", " ", text.lower())
    return cjk + len(re.findall(r"[aeiouyàâäéèêëîïôöùûüáíóúñ]+", latin))


def _semitones(f0: float) -> float:
    return 12 * math.log2(f0 / 100.0)


def _pitch_groups(f0s: dict[int, float | None]) -> list[list[int]]:
    """Offline fallback: men and women apart, then a big pitch gap between
    neighbours starts a new speaker."""
    groups: list[list[tuple[float, int]]] = []
    for gender_lines in ([(f, s) for s, f in f0s.items() if f and f < MALE_BELOW],
                         [(f, s) for s, f in f0s.items() if f and f >= MALE_BELOW]):
        start = len(groups)
        for f, sid in sorted(gender_lines):
            if len(groups) > start and _semitones(f) - _semitones(groups[-1][-1][0]) < SPLIT_SEMITONES:
                groups[-1].append((f, sid))
            else:
                groups.append([(f, sid)])
    while len(groups) > MAX_SPEAKERS:  # merge the two closest groups
        gaps = [abs(_semitones(groups[i + 1][0][0]) - _semitones(groups[i][-1][0])) for i in range(len(groups) - 1)]
        i = gaps.index(min(gaps))
        groups[i: i + 2] = [groups[i] + groups[i + 1]]
    return [[sid for _, sid in g] for g in groups]


def _voiceprint_groups(audio, segments: list[Segment]) -> list[list[int]] | None:
    from . import speakers

    if not speakers.available():
        return None
    vectors = {}
    for s in segments:
        vec = speakers.embed(_part(audio, s))
        if vec is not None:
            vectors[s.id] = vec
    if not vectors:
        return None
    return speakers.cluster(vectors, max_speakers=MAX_SPEAKERS)


def analyze_speakers(audio, segments: list[Segment], voiceprints: bool = True) -> dict:
    """{"labels": {segment id: speaker id}, "speakers": [{"id", "f0", "gender", "lines", "speed"}],
    "loudness": {segment id: rms}, "pitch": {segment id: Hz or None}, "speed": {segment id: syllables/s},
    "method": "voiceprint" | "pitch"} from 16 kHz mono audio."""
    f0s = {s.id: pitch(_part(audio, s)) for s in segments}
    loud = {s.id: loudness(_part(audio, s)) for s in segments}
    speed = {s.id: syllables(s.text) / (s.end - s.start) for s in segments if s.end - s.start > 0.3 and syllables(s.text)}

    groups = _voiceprint_groups(audio, segments) if voiceprints else None
    method = "voiceprint" if groups else "pitch"
    if not groups:
        groups = _pitch_groups(f0s)
    groups = sorted(groups, key=lambda g: min(next(s.start for s in segments if s.id == sid) for sid in g))

    labels: dict[int, int] = {}
    speakers = []
    for n, group in enumerate(groups):
        pitched = sorted(f0s[sid] for sid in group if f0s[sid])
        median = pitched[len(pitched) // 2] if pitched else None
        speeds = sorted(speed[sid] for sid in group if sid in speed)
        speakers.append({"id": n, "f0": round(median, 1) if median else None,
                         "gender": "male" if median and median < MALE_BELOW else "female",
                         "lines": len(group), "speed": round(speeds[len(speeds) // 2], 2) if speeds else None})
        for sid in group:
            labels[sid] = n
    if not speakers:  # nothing voiced (music, whispers): one neutral speaker
        speakers.append({"id": 0, "f0": None, "gender": "female", "lines": 0, "speed": None})
    # lines too short or quiet to measure belong to the speaker of the closest line in time
    timed = sorted(segments, key=lambda s: s.start)
    known = [s for s in timed if s.id in labels]
    for s in timed:
        if s.id not in labels:
            near = min(known, key=lambda k: min(abs(k.end - s.start), abs(s.end - k.start))) if known else None
            labels[s.id] = labels[near.id] if near else speakers[0]["id"]
    for sp in speakers:
        sp["lines"] = sum(1 for v in labels.values() if v == sp["id"])
    return {"labels": labels, "speakers": speakers, "loudness": loud, "pitch": f0s, "speed": speed, "method": method}


def _rate_for(speed: float | None) -> int:
    if not speed:
        return 0
    return max(MIN_RATE, min(MAX_RATE, round((speed / TYPICAL_SPEED - 1) * 100 * RATE_FOLLOW)))


def auto_voices(speakers: list[dict], lang: str) -> dict[int, dict]:
    """speaker id -> {"voice", "pitch" (edge-tts "+NHz"), "shift" (Hz), "base" (voice Hz),
    "rate" (percent), "label"}: closest voice per speaker, different voices (or at
    least clearly different pitches) for different speakers of the same gender."""
    pool = EDGE_VOICES[lang]
    chosen: dict[int, dict] = {}
    for gender in ("male", "female"):
        group = sorted((s for s in speakers if s["gender"] == gender), key=lambda s: -s["lines"])
        voices = pool[gender]
        used: dict[str, list[int]] = {}
        for s in group:  # most talkative speakers choose first
            f0 = s["f0"] or voices[0][1]
            free = ([v for v in voices if v[0] not in used and v[0] not in SPARE_VOICES]
                    or [v for v in voices if v[0] not in used] or voices)
            voice, base, label = min(free, key=lambda v: abs(_semitones(v[1]) - _semitones(f0)))
            shift = max(-MAX_SHIFT, min(MAX_SHIFT, round(f0 - base))) if s["f0"] else 0
            taken = used.setdefault(voice, [])
            while any(abs(shift - t) < SPREAD_FOR_REUSE for t in taken):  # a shared voice: move apart
                shift += SPREAD_FOR_REUSE if f0 >= base else -SPREAD_FOR_REUSE
            taken.append(shift)
            chosen[s["id"]] = {"voice": voice, "pitch": f"{shift:+d}Hz", "shift": shift, "base": base,
                               "rate": _rate_for(s.get("speed")), "label": label}
    return chosen


def line_voices(found: dict, chosen: dict[int, dict]) -> dict[int, dict]:
    """Voice settings per line: the speaker's voice, following the line's own pitch."""
    by_id = {s["id"]: s for s in found["speakers"]}
    out = {}
    for sid, spk in found["labels"].items():
        v = chosen[spk]
        shift = v["shift"]
        f0, own = found["pitch"].get(sid), by_id[spk]["f0"]
        if f0 and own:
            target = v["base"] + shift
            delta = target * (2 ** ((_semitones(f0) - _semitones(own)) * LINE_SHIFT / 12) - 1)
            shift += max(-MAX_LINE_SHIFT, min(MAX_LINE_SHIFT, round(delta)))
        out[sid] = {"voice": v["voice"], "pitch": f"{shift:+d}Hz", "rate": v["rate"], "speaker": spk}
    return out


def describe(speakers: list[dict], voices: dict[int, dict]) -> list[dict]:
    """Human-readable summary for the web page."""
    out = []
    for s in speakers:
        who = "男声" if s["gender"] == "male" else "女声"
        v = voices[s["id"]]
        tweaks = []
        if v.get("shift"):
            tweaks.append(f"音高 {v['shift']:+d} Hz")
        if v.get("rate"):
            tweaks.append(f"语速 {v['rate']:+d}%")
        out.append({
            "speaker": f"说话人 {s['id'] + 1}（{who}" + (f"，约 {int(s['f0'])} Hz" if s["f0"] else "") + f"，{s['lines']} 句）",
            "voice": v["label"] + (f"，{'，'.join(tweaks)}" if tweaks else ""),
        })
    return out
