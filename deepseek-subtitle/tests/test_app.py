import json
import time
from pathlib import Path
from types import SimpleNamespace

from fastapi.testclient import TestClient

from app import main, translator
from app.subtitles import Segment, to_lrc, to_srt, to_vtt


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

    def fake_translate(segments, api_key=None, on_progress=None, target="bilingual"):
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


def test_lrc_and_orig_mode():
    segs = [
        Segment(1, 1.0, 2.0, "こんにちは", zh="你好", en="Hello"),
        Segment(2, 2.2, 3.5, "さようなら", zh="再见", en="Goodbye"),
    ]
    assert to_lrc(segs) == (
        "[00:01.00]你好\n[00:01.00]Hello\n"
        "[00:02.20]再见\n[00:02.20]Goodbye\n[00:03.50]\n"  # gap < 1 s: no blank after line 1
    )
    assert to_lrc(segs, "orig").startswith("[00:01.00]こんにちは\n[00:01.00]你好\n")
    assert "こんにちは\n你好" in to_srt(segs, "orig")


def test_translate_zh_target_only_proofreads(monkeypatch):
    fake = FakeClient()
    fake.calls = 1  # skip the dropped-line first response
    monkeypatch.setattr(translator, "make_client", lambda key=None: fake)
    segs = [Segment(1, 0, 1, "今天天气很好")]
    translator.translate(segs, target="zh")
    assert segs[0].zh == "中今天天气很好" and segs[0].en == ""


def test_export_mp3_embeds_lyrics(tmp_path):
    import subprocess

    import pytest

    from mutagen.id3 import ID3

    from app.export import export_mp3

    src = tmp_path / "in.wav"
    try:
        subprocess.run(
            ["ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=3", str(src)],
            check=True,
        )
    except (FileNotFoundError, subprocess.CalledProcessError):
        pytest.skip("ffmpeg not available to create a test file")
    segs = [Segment(1, 0.5, 2.0, "Hello", zh="你好", en="Hello")]
    out = export_mp3(str(src), tmp_path / "exports", "job1", segs, "bilingual", "演示")
    tags = ID3(out)
    assert tags.getall("USLT")[0].text == "[00:00.50]你好\n[00:00.50]Hello\n[00:02.00]\n"
    assert tags.getall("SYLT")[0].text == [("你好\nHello", 500)]
    assert str(tags["TIT2"]) == "演示"
    assert tags.version[:2] == (2, 3)
    assert (tmp_path / "exports" / "job1.mp3").exists()  # converted audio is cached


def test_zh_target_works_without_key(monkeypatch, tmp_path):
    monkeypatch.setattr(main, "UPLOAD_DIR", tmp_path)
    monkeypatch.delenv("DEEPSEEK_API_KEY", raising=False)
    seen = {}

    def fake_transcribe(path, language=None, on_progress=None):
        seen["language"] = language
        return [Segment(1, 0, 2, "大家好")], "zh"

    monkeypatch.setattr(main.transcriber, "transcribe", fake_transcribe)
    client = TestClient(main.app)

    r = client.post("/api/jobs", files={"file": ("a.mp3", b"x")}, data={"target": "bilingual"})
    assert r.status_code == 400  # bilingual still needs a key

    job_id = client.post("/api/jobs", files={"file": ("a.mp3", b"x")}, data={"target": "zh"}).json()["id"]
    for _ in range(50):
        job = client.get(f"/api/jobs/{job_id}").json()
        if job["status"] in ("done", "error"):
            break
        time.sleep(0.05)
    assert job["status"] == "done", job
    assert seen["language"] == "zh" and job["target"] == "zh"
    assert job["segments"][0]["zh"] == "大家好"
    assert client.get(f"/api/jobs/{job_id}/subtitle.lrc?mode=zh").text == "[00:00.00]大家好\n[00:02.00]\n"


def test_active_segment_and_wrap():
    from app.burn import _ActiveSegment, _wrap

    segs = [Segment(1, 1.0, 2.0, "a"), Segment(2, 3.0, 4.0, "b")]
    tracker = _ActiveSegment(segs)
    assert [getattr(tracker.at(t), "id", None) for t in (0.5, 1.5, 2.5, 3.5, 4.5)] == [None, 1, None, 2, None]
    assert tracker.at(1.2).id == 1  # time going backwards (seek) still works

    font = SimpleNamespace(getlength=len)  # 1 unit per character
    assert _wrap("hello big world", font, 9) == ["hello big", "world"]
    assert _wrap("一二三四五六七", font, 3) == ["一二三", "四五六", "七"]


def _make_media(tmp_path, args, name):
    import subprocess

    import pytest

    from app.burn import find_font

    try:
        find_font()
    except RuntimeError:
        pytest.skip("no CJK font installed")
    path = tmp_path / name
    try:
        subprocess.run(["ffmpeg", "-loglevel", "error", *args, str(path)], check=True)
    except (FileNotFoundError, subprocess.CalledProcessError):
        pytest.skip("ffmpeg not available to create a test file")
    return path


def _frame_at(path, t):
    import av

    with av.open(str(path)) as c:
        for frame in c.decode(video=0):
            if frame.time >= t:
                return frame.to_ndarray(format="rgb24")


def test_burn_video_draws_subtitles(tmp_path):
    import av

    from app.burn import burn

    src = _make_media(tmp_path, [
        "-f", "lavfi", "-i", "color=c=blue:s=320x240:d=3:r=10",
        "-f", "lavfi", "-i", "sine=duration=3", "-shortest", "-pix_fmt", "yuv420p",
    ], "in.mp4")
    out = tmp_path / "out.mp4"
    progress = []
    burn(str(src), str(out), [Segment(1, 1.0, 2.0, "x", zh="你好", en="Hello")], "bilingual", progress.append)
    with av.open(str(out)) as c:
        assert (c.streams.video[0].width, c.streams.video[0].height) == (320, 240)
        assert c.streams.audio, "audio track kept"
    bottom = slice(150, 240)
    before, during = _frame_at(out, 0.5)[bottom], _frame_at(out, 1.5)[bottom]
    assert abs(before.astype(int) - during.astype(int)).max() > 100  # white text appeared
    assert progress[-1] == 1.0


def test_burn_audio_only_makes_black_video(tmp_path):
    import av

    from app.burn import AUDIO_ONLY_SIZE, burn

    src = _make_media(tmp_path, ["-f", "lavfi", "-i", "sine=duration=2"], "in.mp3")
    out = tmp_path / "out.mp4"
    burn(str(src), str(out), [Segment(1, 0.2, 1.8, "x", zh="只有声音")], "zh")
    with av.open(str(out)) as c:
        v = c.streams.video[0]
        assert (v.width, v.height) == AUDIO_ONLY_SIZE
    frame = _frame_at(out, 1.0)
    assert frame[:300].max() < 30 and frame[600:].max() > 200  # black picture, white subtitle at the bottom


def test_burn_api(monkeypatch, tmp_path):
    monkeypatch.setattr(main, "UPLOAD_DIR", tmp_path)
    monkeypatch.setattr(main, "EXPORT_DIR", tmp_path / "exports")
    monkeypatch.setattr(main.transcriber, "transcribe",
                        lambda path, language=None, on_progress=None: ([Segment(1, 0, 1, "hi")], "en"))
    monkeypatch.setattr(main.translator, "translate", lambda segments, **kw: segments)

    def fake_burn(src, dst, segments, mode, on_progress=None):
        on_progress(0.5)
        Path(dst).write_bytes(b"video:" + mode.encode())

    monkeypatch.setattr(main, "burn", fake_burn)
    client = TestClient(main.app)
    job_id = client.post("/api/jobs", files={"file": ("a.mp4", b"x")}, data={"api_key": "k"}).json()["id"]

    def wait(pred):
        for _ in range(50):
            job = client.get(f"/api/jobs/{job_id}").json()
            if pred(job):
                return job
            time.sleep(0.05)
        raise AssertionError(job)

    wait(lambda j: j["status"] == "done")
    assert client.get(f"/api/jobs/{job_id}/burned.mp4").status_code == 409  # not burned yet
    assert client.post(f"/api/jobs/{job_id}/burn", json={"mode": "bad"}).status_code == 400
    assert client.post(f"/api/jobs/{job_id}/burn", json={"mode": "zh"}).status_code == 200
    job = wait(lambda j: j["burn"]["status"] == "done")
    assert job["burn"]["mode"] == "zh"
    r = client.get(f"/api/jobs/{job_id}/burned.mp4")
    assert r.content == b"video:zh" and "a.zh.subtitled.mp4" in r.headers["content-disposition"]
