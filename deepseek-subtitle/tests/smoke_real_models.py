"""Smoke test with the real models (run by .github/workflows/windows.yml; not part of pytest).

    python -m tests.smoke_real_models clone          # voiceprints + cloned dub, as the app does it
    python -m tests.smoke_real_models same-process   # onnxruntime and sherpa-onnx in ONE process

"clone" must pass. "same-process" checks the suspected cause of the Windows crash
(sherpa-onnx's bundled onnxruntime.dll next to the onnxruntime package); the app
avoids it by synthesising in a separate worker process.
"""

from __future__ import annotations

import sys
import time
import urllib.request
from pathlib import Path

SAMPLE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/0-four-speakers-zh.wav"
WORK = Path("data/smoke")


def sample() -> Path:
    WORK.mkdir(parents=True, exist_ok=True)
    path = WORK / "four-speakers-zh.wav"
    if not path.exists():
        urllib.request.urlretrieve(SAMPLE, path)
    return path


def segments(path: Path):
    """Subtitle-like lines from a simple energy VAD."""
    import numpy as np

    from app.subtitles import Segment
    from app.transcriber import decode_audio

    a = decode_audio(str(path), 16000)
    hop = 800
    energy = np.array([np.sqrt(np.mean(a[i:i + hop] ** 2)) for i in range(0, len(a) - hop, hop)])
    on = energy > max(0.01, np.percentile(energy, 30) * 2)
    segs, start, quiet = [], None, 0
    for i, v in enumerate(on):
        if v:
            start = i if start is None else start
            quiet = 0
        elif start is not None:
            quiet += 1
            if quiet >= 6:
                if (i - quiet - start) * 0.05 > 0.4:
                    segs.append(Segment(len(segs) + 1, start * 0.05, (i - quiet + 1) * 0.05, "我们今天来说一说这件事情",
                                        zh="大家好，这是一段克隆的配音。"))
                start = None
    return segs


def clone() -> None:
    from app import clone as cloning, dub, speakers

    path = sample()
    assert speakers.available(), "voiceprint model unavailable"
    segs = segments(path)
    t = time.time()
    out, summary = dub.dub(str(path), WORK / "dubbed", segs, "zh", bg_volume=0, clone=True)
    cloning.shutdown()
    for row in summary:
        print(row)
    print(f"dubbed {len(segs)} lines in {time.time() - t:.0f} s -> {out} ({out.stat().st_size} bytes)")
    assert out.stat().st_size > 10000
    assert not any("注意" in row["speaker"] for row in summary), "cloning fell back to edge voices"
    assert sum("克隆原声" in row["voice"] for row in summary) >= 3, "expected the speakers to be cloned"


def same_process() -> None:
    import numpy as np

    from app import clone as cloning, speakers

    assert speakers.available()  # loads the onnxruntime package
    print("voiceprint ok:", speakers.embed(np.random.default_rng(0).standard_normal(32000).astype(np.float32) * 0.1)[:3])
    cloning.prepare()  # downloads the models
    cloning.shutdown()
    import sherpa_onnx  # noqa: F401  (now in the same process as onnxruntime)

    tts = cloning.build_tts()
    ref = np.sin(2 * np.pi * 180 * np.arange(48000) / 16000).astype(np.float32) * 0.3
    audio = tts.generate("你好，世界。", "这是一段参考音频。", ref.tolist(), 16000, 1.0, cloning.STEPS)
    print("same-process synthesis ok:", len(audio.samples), "samples")


if __name__ == "__main__":
    {"clone": clone, "same-process": same_process}[sys.argv[1]]()
