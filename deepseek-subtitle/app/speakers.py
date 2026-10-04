"""Who is speaking: speaker recognition from voiceprints.

Every subtitle line gets a voiceprint (a 192-number speaker embedding from
3D-Speaker's CAM++ model, run with onnxruntime, which RapidOCR already
installs) and lines whose voiceprints are alike are one speaker. This tells
apart two men with the same pitch, which pitch alone cannot.

The model (about 28 MB, Apache-2.0, https://github.com/modelscope/3D-Speaker,
ONNX export from https://github.com/k2-fsa/sherpa-onnx) is downloaded on first
use into data/models/. Without it (offline), voices.py falls back to pitch.
"""

from __future__ import annotations

import logging
import os
import threading
import urllib.request
from pathlib import Path

SR = 16000
MODEL_NAME = "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
MODEL_URL = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/{MODEL_NAME}"
# GitHub is slow or blocked on some networks (e.g. in mainland China): try mirrors too
MIRRORS = ("", "https://ghfast.top/", "https://gh-proxy.com/")
MODEL_BYTES = 28_000_000  # a smaller file is a broken download
MODEL_DIR = Path(os.getenv("MODEL_DIR", Path(__file__).resolve().parent.parent / "data" / "models"))

MIN_SECONDS = 1.0  # shorter lines give unreliable voiceprints
SAME_SPEAKER = 0.55  # cosine similarity of two clusters' mean voiceprints to merge them
MAX_SPEAKERS = 6

log = logging.getLogger("subtitle")
_session = None
_lock = threading.Lock()


# ---- Kaldi-style log-mel filterbank (what the model was trained on) --------

def _mel(f):
    import numpy as np

    return 1127.0 * np.log(1.0 + np.asarray(f) / 700.0)


def _mel_banks(n_fft: int = 512, bins: int = 80, low: float = 20.0, high: float = SR / 2):
    import numpy as np

    lo, hi = _mel(low), _mel(high)
    centers = lo + (hi - lo) / (bins + 1) * np.arange(bins + 2)
    freqs = _mel(np.arange(n_fft // 2) * SR / n_fft)
    banks = np.zeros((bins, n_fft // 2 + 1), dtype=np.float32)
    for m in range(bins):
        left, center, right = centers[m], centers[m + 1], centers[m + 2]
        up = (freqs - left) / (center - left)
        down = (right - freqs) / (right - center)
        banks[m, : n_fft // 2] = np.maximum(0, np.minimum(up, down))
    return banks


_BANKS = None


def fbank(samples):
    """80-dim log-mel features, 25 ms frames every 10 ms (Kaldi defaults, no dither)."""
    import numpy as np

    global _BANKS
    if _BANKS is None:
        _BANKS = _mel_banks()
    length, shift, n_fft = 400, 160, 512
    samples = np.asarray(samples, dtype=np.float32)  # in [-1, 1], as the model expects
    if len(samples) < length:
        return np.zeros((0, 80), dtype=np.float32)
    count = 1 + (len(samples) - length) // shift
    idx = np.arange(length)[None, :] + shift * np.arange(count)[:, None]
    frames = samples[idx].astype(np.float64)
    frames -= frames.mean(axis=1, keepdims=True)
    frames[:, 1:] -= 0.97 * frames[:, :-1]
    frames[:, 0] -= 0.97 * frames[:, 0]
    window = (0.5 - 0.5 * np.cos(2 * np.pi * np.arange(length) / (length - 1))) ** 0.85  # povey
    power = np.abs(np.fft.rfft(frames * window, n_fft)) ** 2
    energies = power @ _BANKS.T.astype(np.float64)
    return np.log(np.maximum(energies, np.finfo(np.float32).eps)).astype(np.float32)


# ---- model ------------------------------------------------------------------

def _download(dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_suffix(".part")
    errors = []
    for mirror in MIRRORS:
        try:
            with urllib.request.urlopen(mirror + MODEL_URL, timeout=30) as r, part.open("wb") as f:
                while chunk := r.read(1 << 20):
                    f.write(chunk)
            if part.stat().st_size >= MODEL_BYTES:
                part.replace(dest)
                return
            errors.append(f"{mirror or 'github'}: 文件不完整")
        except Exception as e:  # try the next mirror
            errors.append(f"{mirror or 'github'}: {e}")
    part.unlink(missing_ok=True)
    raise RuntimeError("下载声纹模型失败：" + "；".join(errors))


def _get_session():
    global _session
    with _lock:
        if _session is None:
            import onnxruntime as ort

            path = MODEL_DIR / MODEL_NAME
            if not path.exists():
                _download(path)
            _session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    return _session


def available() -> bool:
    """Whether voiceprints can be computed (model present or downloadable)."""
    try:
        _get_session()
        return True
    except Exception as e:
        log.warning("声纹识别不可用，改用音高区分说话人：%s", e)
        return False


def embed(samples):
    """Unit-length voiceprint of 16 kHz mono float samples, None if too short."""
    import numpy as np

    if len(samples) < MIN_SECONDS * SR:
        return None
    feats = fbank(samples)
    feats -= feats.mean(axis=0, keepdims=True)  # the model's "global-mean" normalisation
    session = _get_session()
    vec = session.run(None, {session.get_inputs()[0].name: feats[None]})[0][0]
    norm = float(np.linalg.norm(vec))
    return vec / norm if norm else None


# ---- clustering ---------------------------------------------------------------

def cluster(vectors: dict[int, object], threshold: float = SAME_SPEAKER,
            max_speakers: int = MAX_SPEAKERS) -> list[list[int]]:
    """Group ids by voiceprint: agglomerative clustering on the mean voiceprints,
    merging the most similar pair while it is above `threshold` (or while there
    are more than `max_speakers` groups). Biggest group first."""
    import numpy as np

    groups = [[k] for k in vectors]
    sums = [np.array(vectors[k], dtype=np.float64) for k in vectors]

    def sim(i, j):
        a, b = sums[i], sums[j]
        return float(a @ b / (np.linalg.norm(a) * np.linalg.norm(b)))

    while len(groups) > 1:
        best, pair = -2.0, None
        for i in range(len(groups)):
            for j in range(i + 1, len(groups)):
                s = sim(i, j)
                if s > best:
                    best, pair = s, (i, j)
        if best < threshold and len(groups) <= max_speakers:
            break
        i, j = pair
        groups[i] += groups.pop(j)
        sums[i] = sums[i] + sums.pop(j)
    return sorted(groups, key=len, reverse=True)
