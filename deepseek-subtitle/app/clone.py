"""Voice cloning for dubbing: the dub speaks in the speakers' own voices.

Free and offline, with open-source models from GitHub:
- ZipVoice (https://github.com/k2-fsa/ZipVoice, Apache-2.0), a zero-shot
  Chinese / English TTS: given a few seconds of someone's voice and what they
  said, it speaks any text in that voice (across languages too: an English
  speaker's voice can speak the Chinese dub);
- the Vocos vocoder (MIT);
- run with sherpa-onnx (https://github.com/k2-fsa/sherpa-onnx, Apache-2.0)
  on the CPU. The models (about 160 MB, int8) are downloaded on first use
  into data/models/.

For every speaker found in the video (voices.py), about 8 s of their clearest
lines and the transcript of those lines are cut from the original audio; that
is the voice every dubbed line of the speaker is spoken in.
"""

from __future__ import annotations

import os
import re
import tarfile
import threading
import urllib.request
import wave
from pathlib import Path

from .speakers import MIRRORS, MODEL_DIR
from .subtitles import Segment

RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
ZIPVOICE = "sherpa-onnx-zipvoice-distill-int8-zh-en-emilia"
ZIPVOICE_URL = f"{RELEASES}/tts-models/{ZIPVOICE}.tar.bz2"
VOCODER = "vocos_24khz.onnx"
VOCODER_URL = f"{RELEASES}/vocoder-models/{VOCODER}"
NEEDED = ("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt", "lexicon.txt")
SIZE_MB = 160

SR = 16000  # reference clips are cut from the 16 kHz analysis audio
REF_SECONDS = 8.0  # ZipVoice works best with a short reference
REF_MAX = 12.0
LINE_MIN, LINE_MAX = 1.5, 10.0  # lines usable in a reference
GAP = 0.2  # silence between lines in the reference
STEPS = 4  # flow-matching steps of the distilled model

WORKER_MODULE = "app.clone_worker"
MAX_RESTARTS = 2  # worker crashes tolerated per dub before giving up on cloning
READY_TIMEOUT = 300  # seconds to load the model
LINE_TIMEOUT = 300  # seconds for one line

_lock = threading.Lock()  # one synthesis at a time; each uses all CPU threads
_worker = None  # the synthesis subprocess (see clone_worker.py)
_restarts = 0
_failure = ""  # why cloning stopped in this dub ("" = it works)


class CloneError(RuntimeError):
    pass


def model_dir() -> Path:
    return MODEL_DIR / ZIPVOICE


def downloaded() -> bool:
    d = model_dir()
    return all((d / f).exists() for f in NEEDED) and (d / "espeak-ng-data").is_dir() and (MODEL_DIR / VOCODER).exists()


def _open(url: str):
    errors = []
    for mirror in MIRRORS:
        try:
            return urllib.request.urlopen(mirror + url, timeout=30)
        except Exception as e:  # try the next mirror
            errors.append(f"{mirror or 'github'}: {e}")
    raise CloneError("下载声音克隆模型失败：" + "；".join(errors))


def download() -> None:
    """Fetch the ZipVoice model (only the files used) and the vocoder."""
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    if not (MODEL_DIR / VOCODER).exists():
        part = MODEL_DIR / (VOCODER + ".part")
        with _open(VOCODER_URL) as r, part.open("wb") as f:
            while chunk := r.read(1 << 20):
                f.write(chunk)
        part.replace(MODEL_DIR / VOCODER)
    d = model_dir()
    if all((d / f).exists() for f in NEEDED) and (d / "espeak-ng-data").is_dir():
        return
    tmp = MODEL_DIR / (ZIPVOICE + ".part")
    try:
        with _open(ZIPVOICE_URL) as r, tarfile.open(fileobj=r, mode="r|bz2") as tar:
            for member in tar:
                name = member.name.split("/", 1)[1] if "/" in member.name else ""
                if not member.isfile() or not (name in NEEDED or name.startswith("espeak-ng-data/")):
                    continue
                target = tmp / name
                if ".." in Path(name).parts:
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                with tar.extractfile(member) as src, target.open("wb") as out:
                    while chunk := src.read(1 << 20):
                        out.write(chunk)
    except CloneError:
        raise
    except Exception as e:
        raise CloneError(f"下载声音克隆模型失败（网络中断？可以重试）：{e}") from e
    if not all((tmp / f).exists() for f in NEEDED):
        raise CloneError("声音克隆模型下载不完整，请重试")
    if d.exists():
        import shutil

        shutil.rmtree(d)
    tmp.replace(d)


def build_tts():
    """The ZipVoice synthesiser; only ever created in the worker process."""
    import sherpa_onnx

    d = model_dir()
    cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        zipvoice=sherpa_onnx.OfflineTtsZipvoiceModelConfig(
            tokens=str(d / "tokens.txt"), encoder=str(d / "encoder.int8.onnx"),
            decoder=str(d / "decoder.int8.onnx"), vocoder=str(MODEL_DIR / VOCODER),
            data_dir=str(d / "espeak-ng-data"), lexicon=str(d / "lexicon.txt")),
        num_threads=max(1, min(8, os.cpu_count() or 2))))
    return sherpa_onnx.OfflineTts(cfg)


class CloneCrash(CloneError):
    """The synthesis process died (native crash)."""


def _log_path() -> Path:
    return MODEL_DIR.parent / "clone-worker.log"


def _log_tail(n: int = 600) -> str:
    try:
        return _log_path().read_text(encoding="utf-8", errors="replace")[-n:].strip()
    except OSError:
        return ""


class _Worker:
    """ZipVoice runs in its own process: on Windows, sherpa-onnx's bundled
    onnxruntime.dll and the onnxruntime package (voiceprints, OCR) can't share
    one process, and a native crash there must not take the web server down."""

    def __init__(self):
        import subprocess
        import sys

        _log_path().parent.mkdir(parents=True, exist_ok=True)
        self.log = _log_path().open("ab")
        flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        self.proc = subprocess.Popen(
            [sys.executable, "-m", WORKER_MODULE],
            cwd=str(Path(__file__).resolve().parent.parent), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=self.log, creationflags=flags, env={**os.environ, "MODEL_DIR": str(MODEL_DIR)})
        self._call(None, READY_TIMEOUT)  # wait until the model is loaded

    def _died(self) -> CloneCrash:
        code = self.proc.poll()
        how = f"退出代码 {code}"
        if code is not None and (code < 0 or code > 255):  # Windows crash codes, e.g. 0xC0000005
            how += f"（0x{code & 0xFFFFFFFF:08X}）"
        tail = _log_tail()
        return CloneCrash(f"声音克隆进程意外退出（{how}）" + (f"：{tail[-300:]}" if tail else ""))

    def _call(self, request: dict | None, timeout: float) -> dict:
        import json

        if self.proc.poll() is not None:
            raise self._died()
        result: list = []

        def read():
            result.append(self.proc.stdout.readline())

        try:
            if request is not None:
                self.proc.stdin.write((json.dumps(request, ensure_ascii=False) + "\n").encode("utf-8"))
                self.proc.stdin.flush()
            reader = threading.Thread(target=read, daemon=True)
            reader.start()
            reader.join(timeout)
        except OSError:
            raise self._died() from None
        if not result:
            self.close()
            raise CloneCrash("声音克隆超时，进程没有响应")
        if not result[0]:
            raise self._died()
        reply = json.loads(result[0])
        if reply.get("error"):
            raise CloneError(reply["error"])
        return reply

    def speak(self, request: dict) -> None:
        self._call(request, LINE_TIMEOUT)

    def close(self) -> None:
        try:
            self.proc.kill()
        except OSError:
            pass
        self.log.close()


def prepare() -> None:
    """Download (first time) and start the synthesis process; raises CloneError.
    Called at the start of each dub (resets the crash budget)."""
    global _worker, _restarts, _failure
    with _lock:
        _restarts, _failure = 0, ""
        import importlib.util

        # only check that it is installed: importing it here would load its onnxruntime.dll
        # into the web server process, which is what the worker process avoids
        if importlib.util.find_spec("sherpa_onnx") is None:
            raise CloneError("缺少 sherpa-onnx：请重新运行启动脚本安装依赖（pip install sherpa-onnx）")
        if not downloaded():
            download()
        if _worker is None or _worker.proc.poll() is not None:
            _worker = _Worker()


def failure() -> str:
    """Why cloning stopped working during this dub, "" if it didn't."""
    return _failure


def shutdown() -> None:
    global _worker
    with _lock:
        if _worker is not None:
            _worker.close()
            _worker = None


def reference(audio, segments: list[Segment], line_ids: list[int], loudness: dict[int, float]):
    """{"samples" (16 kHz float32), "text"} of a speaker's clearest lines, about REF_SECONDS
    long, or None if the speaker has no usable line."""
    import numpy as np

    mine = [s for s in segments if s.id in line_ids and s.text.strip()]
    lines = [s for s in mine if LINE_MIN <= s.end - s.start <= LINE_MAX]
    if not lines:  # only short (or very long) lines: take the ones closest to a good length
        lines = sorted(mine, key=lambda s: abs((s.end - s.start) - 4))[:3]
    if not lines:
        return None
    picked, total = [], 0.0
    for s in sorted(lines, key=lambda s: loudness.get(s.id, 0), reverse=True):
        length = min(s.end - s.start, REF_MAX)
        if total >= REF_SECONDS or (picked and total + length > REF_MAX):
            continue
        picked.append(s)
        total += length
    picked.sort(key=lambda s: s.start)
    gap = np.zeros(int(GAP * SR), dtype=np.float32)
    parts = []
    for s in picked:
        parts += [audio[int(s.start * SR): int(min(s.end, s.start + REF_MAX) * SR)], gap]
    clip = np.concatenate(parts[:-1]).astype(np.float32)
    peak = float(np.abs(clip).max() or 1.0)
    if peak > 0.95:
        clip *= 0.95 / peak
    joiner = "" if re.search(r"[぀-ヿ㐀-鿿]", "".join(s.text for s in picked)) else " "
    return {"samples": clip, "text": joiner.join(s.text.strip() for s in picked)}


def _reference_wav(ref: dict) -> str:
    """The reference clip as a WAV file for the worker (written once per speaker)."""
    import tempfile

    import numpy as np

    if not ref.get("wav") or not os.path.exists(ref["wav"]):
        fd, path = tempfile.mkstemp(suffix=".wav", prefix="clone_ref_")
        os.close(fd)
        with wave.open(path, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes((np.clip(ref["samples"], -1, 1) * 32767).astype("<i2").tobytes())
        ref["wav"] = path
    return ref["wav"]


def speak(ref: dict, text: str, path: str, speed: float = 1.0) -> None:
    """Write `text` spoken in the reference voice to `path` (WAV). Raises CloneCrash when
    the synthesis process died (it is restarted for the next line, up to MAX_RESTARTS
    times per dub; after that every call raises at once)."""
    global _worker, _restarts, _failure
    with _lock:
        if _failure:
            raise CloneCrash(_failure)
        try:
            if _worker is None or _worker.proc.poll() is not None:
                if _restarts >= MAX_RESTARTS:
                    raise CloneCrash(_failure or "声音克隆进程多次崩溃")
                _restarts += 1
                _worker = _Worker()
            _worker.speak({"text": text, "ref_wav": _reference_wav(ref), "ref_text": ref["text"],
                           "speed": round(max(0.5, min(2.0, speed)), 3), "out": path})
        except CloneCrash as e:
            if _worker is not None:
                _worker.close()
                _worker = None
            if _restarts >= MAX_RESTARTS:
                _failure = str(e)
            raise
