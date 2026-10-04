"""The voice-cloning process (started by clone.py; not meant to be run by hand).

Loads ZipVoice once, then answers one JSON request per stdin line:
{"text", "ref_wav", "ref_text", "speed", "out"} -> {"ok": true} or {"error": "..."}.
Only this process imports sherpa-onnx (see clone._Worker for why). Its log
messages go to stderr, which clone.py sends to data/clone-worker.log.
"""

from __future__ import annotations

import json
import sys
import wave


def _write_wav(path: str, samples, rate: int) -> None:
    import numpy as np

    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes((np.clip(np.asarray(samples, dtype=np.float32), -1, 1) * 32767).astype("<i2").tobytes())


def _read_wav(path: str):
    import numpy as np

    with wave.open(path, "rb") as w:
        rate = w.getframerate()
        data = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32) / 32768.0
    return data, rate


def main() -> None:
    from app.clone import STEPS, build_tts

    out = sys.stdout
    sys.stdout = sys.stderr  # stray prints must not corrupt the replies
    try:
        tts = build_tts()
    except Exception as e:
        out.write(json.dumps({"error": f"加载声音克隆模型失败：{e}"}, ensure_ascii=False) + "\n")
        out.flush()
        return
    out.write(json.dumps({"ready": True}) + "\n")
    out.flush()
    refs: dict[str, tuple] = {}
    for line in sys.stdin:
        if not line.strip():
            continue
        try:
            req = json.loads(line)
            if req["ref_wav"] not in refs:
                refs[req["ref_wav"]] = _read_wav(req["ref_wav"])
            samples, rate = refs[req["ref_wav"]]
            audio = tts.generate(req["text"], req["ref_text"], samples.tolist(), rate, float(req["speed"]), STEPS)
            if not len(audio.samples):
                raise RuntimeError(f"没有生成声音：{req['text'][:20]}")
            _write_wav(req["out"], audio.samples, audio.sample_rate)
            reply = {"ok": True}
        except Exception as e:
            reply = {"error": f"声音克隆合成失败：{e}"}
        out.write(json.dumps(reply, ensure_ascii=False) + "\n")
        out.flush()


if __name__ == "__main__":
    main()
