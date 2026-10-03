import json
import time
from types import SimpleNamespace

from fastapi.testclient import TestClient

from app import main, translator
from app.subtitles import Segment, to_srt, to_vtt


def _segs():
    return [
        Segment(1, 0.0, 1.5, "Hello world", zh="你好，世界", en="Hello world"),
        Segment(2, 3661.25, 3662.0, "你好", zh="你好", en="Hi"),
    ]


def test_srt_bilingual():
    assert to_srt(_segs()) == (
        "1\n00:00:00,000 --> 00:00:01,500\n你好，世界\nHello world\n\n"
        "2\n01:01:01,250 --> 01:01:02,000\n你好\nHi\n"
    )


def test_srt_single_language_and_vtt():
    assert "Hello world" not in to_srt(_segs(), "zh")
    assert "你好" not in to_srt(_segs(), "en")
    vtt = to_vtt(_segs())
    assert vtt.startswith("WEBVTT\n\n00:00:00.000 --> 00:00:01.500\n")


class FakeClient:
    """Mimics the OpenAI SDK; drops a line on the first call to exercise retry."""

    def __init__(self):
        self.calls = 0
        self.chat = SimpleNamespace(completions=SimpleNamespace(create=self.create))

    def create(self, messages, **kwargs):
        self.calls += 1
        body = json.loads(messages[1]["content"].split("\n", 1)[1])
        items = [{"id": l["id"], "zh": "中" + l["text"], "en": "EN " + l["text"]} for l in body["lines"]]
        if self.calls == 1:
            items = items[:-1]
        content = json.dumps({"items": items}, ensure_ascii=False)
        return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=content))])


def test_translate_batches_and_retries(monkeypatch):
    fake = FakeClient()
    monkeypatch.setattr(translator, "make_client", lambda key=None: fake)
    segs = [Segment(i, i, i + 1, f"line{i}") for i in range(1, 36)]
    translator.translate(segs)
    assert segs[0].zh == "中line1" and segs[-1].en == "EN line35"
    assert fake.calls == 3  # batch 1 (retry once) + batch 2


def test_full_job_flow(monkeypatch, tmp_path):
    monkeypatch.setattr(main, "UPLOAD_DIR", tmp_path)
    monkeypatch.setattr(
        main.transcriber, "transcribe",
        lambda path, language=None, on_progress=None: ([Segment(1, 0, 2, "Hello")], "en"),
    )

    def fake_translate(segments, api_key=None, on_progress=None):
        segments[0].zh, segments[0].en = "你好", "Hello"
        return segments

    monkeypatch.setattr(main.translator, "translate", fake_translate)
    client = TestClient(main.app)

    r = client.post("/api/jobs", files={"file": ("a.txt", b"x")}, data={"api_key": "k"})
    assert r.status_code == 400

    r = client.post("/api/jobs", files={"file": ("demo.mp3", b"fake-audio")}, data={"api_key": "sk-test"})
    job_id = r.json()["id"]
    for _ in range(50):
        job = client.get(f"/api/jobs/{job_id}").json()
        if job["status"] in ("done", "error"):
            break
        time.sleep(0.05)
    assert job["status"] == "done", job

    r = client.put(f"/api/jobs/{job_id}/segments", json={"segments": [{"id": 1, "zh": "您好"}]})
    assert r.status_code == 200
    srt = client.get(f"/api/jobs/{job_id}/subtitle.srt?download=true")
    assert "您好\nHello" in srt.text
    assert "attachment" in srt.headers["content-disposition"]
    assert client.get(f"/api/jobs/{job_id}/media").content == b"fake-audio"
    assert "中英字幕生成器" in client.get("/").text


def test_decode_audio(tmp_path):
    import math
    import struct
    import wave

    from app.transcriber import decode_audio

    path = tmp_path / "tone.wav"
    with wave.open(str(path), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(44100)
        w.writeframes(b"".join(
            struct.pack("<hh", v, v)
            for v in (int(8000 * math.sin(2 * math.pi * 440 * i / 44100)) for i in range(44100))
        ))
    audio = decode_audio(str(path))
    assert audio.dtype.name == "float32"
    assert abs(len(audio) - 16000) < 100  # 1 s resampled to 16 kHz mono
    assert 0.2 < abs(audio).max() < 0.3  # amplitude 8000/32768 preserved


class FakeWhisper:
    """Stands in for faster_whisper.WhisperModel; CUDA fails like a missing cuBLAS."""

    created = []

    def __init__(self, name, device, compute_type):
        self.device = device
        FakeWhisper.created.append((device, compute_type))

    def transcribe(self, audio, **kwargs):
        if self.device == "cuda":
            raise RuntimeError("Library cublas64_12.dll is not found or cannot be loaded")
        seg = SimpleNamespace(start=0.0, end=1.0, text=" hi ")
        return iter([seg]), SimpleNamespace(duration=1.0, language="en")


def _patch_whisper(monkeypatch):
    import faster_whisper

    from app import transcriber

    FakeWhisper.created = []
    monkeypatch.setattr(faster_whisper, "WhisperModel", FakeWhisper)
    monkeypatch.setattr(transcriber, "_models", {})
    monkeypatch.setattr(transcriber, "_cuda_failed", False)
    monkeypatch.setattr(transcriber, "decode_audio", lambda path: [0.0])
    return transcriber


def test_cpu_is_default(monkeypatch):
    transcriber = _patch_whisper(monkeypatch)
    monkeypatch.delenv("WHISPER_DEVICE", raising=False)
    segs, lang = transcriber.transcribe("x.mp3")
    assert [s.text for s in segs] == ["hi"] and lang == "en"
    assert FakeWhisper.created == [("cpu", "int8")]


def test_gpu_failure_falls_back_to_cpu(monkeypatch):
    transcriber = _patch_whisper(monkeypatch)
    monkeypatch.setenv("WHISPER_DEVICE", "cuda")
    segs, _ = transcriber.transcribe("x.mp3")
    assert [s.text for s in segs] == ["hi"]
    assert [d for d, _ in FakeWhisper.created] == ["cuda", "cpu"]
    transcriber.transcribe("x.mp3")  # later jobs skip the broken GPU
    assert [d for d, _ in FakeWhisper.created] == ["cuda", "cpu"]
