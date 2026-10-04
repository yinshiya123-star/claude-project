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
    """Mimics the OpenAI SDK by filling in the JSON template at the end of each prompt."""

    def __init__(self, drop_first=None, fail=()):
        self.prompts = []
        self.drop_first = drop_first  # step whose first answer misses a line (exercises retry)
        self.fail = set(fail)  # steps that always fail
        self.chat = SimpleNamespace(completions=SimpleNamespace(create=self.create))

    @staticmethod
    def step(prompt):
        if "Term Extraction" in prompt:
            return "summary"
        if '"free"' in prompt:
            return "free"
        if '"direct"' in prompt:
            return "direct"
        return "correct"

    def create(self, messages, **kwargs):
        assert "json" in messages[0]["content"]  # DeepSeek's JSON mode requirement
        prompt = messages[-1]["content"]
        self.prompts.append(prompt)
        step = self.step(prompt)
        if step in self.fail:
            raise RuntimeError("boom")
        if step == "summary":
            data = {"theme": "一个测试视频。", "terms": [{"src": "line1", "zh": "一号", "en": "One", "note": "术语"}]}
        else:
            template = json.loads(prompt[prompt.rindex("## Output in only JSON format and no other text") + 48:])
            lang = "zh" if "Simplified Chinese" in prompt.split("## INPUT")[0] else "en"
            for item in template.values():
                origin = item["origin"]
                item.update({"direct": f"{lang}直译:{origin}", "free": f"{lang}意译:{origin}", "fixed": f"校:{origin}"})
            if self.drop_first == step:
                self.drop_first = None
                template.pop(str(len(template)))
            data = template
        content = json.dumps(data, ensure_ascii=False)
        return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=content))])


def _use_fake(monkeypatch, **kw):
    fake = FakeClient(**kw)
    monkeypatch.setattr(translator, "make_client", lambda key=None: fake)
    return fake


def test_translate_foreign_source_two_steps(monkeypatch):
    fake = _use_fake(monkeypatch)
    segs = [Segment(i, i, i + 1, f"line{i}") for i in range(1, 26)]
    info = translator.translate(segs, source_lang="ja")
    # recognition text corrected first, then translated from the corrected text
    assert segs[0].text == "校:line1"
    assert segs[0].zh == "zh意译:校:line1" and segs[24].en == "en意译:校:line25"
    assert info["theme"] == "一个测试视频。" and info["terms"][0]["zh"] == "一号"
    steps = [FakeClient.step(p) for p in fake.prompts]
    # summary + 3 chunks (10 lines max) x (correction + 2 languages x (faithful + reflect))
    assert steps.count("summary") == 1 and steps.count("correct") == 3
    assert steps.count("direct") == 6 and steps.count("free") == 6
    first_chunk = next(p for p in fake.prompts if FakeClient.step(p) == "direct" and '"origin": "校:line1"' in p)
    assert "Japanese" in first_chunk and 'line1: Chinese "一号"' in first_chunk  # term passed as context
    second_chunk = next(p for p in fake.prompts if FakeClient.step(p) == "direct" and '"origin": "校:line11"' in p)
    assert "<previous_content>\n校:line6\n校:line7\n校:line8\n校:line9\n校:line10\n</previous_content>" in second_chunk
    assert "<subsequent_content>\n校:line21\n校:line22\n校:line23\n</subsequent_content>" in second_chunk


def test_translate_english_source_without_reflection(monkeypatch):
    fake = _use_fake(monkeypatch)
    segs = [Segment(1, 0, 1, "Hello there")]
    translator.translate(segs, source_lang="en", reflect=False)
    assert segs[0].zh == "zh直译:校:Hello there" and segs[0].en == "校:Hello there"
    assert [FakeClient.step(p) for p in fake.prompts] == ["summary", "correct", "direct"]


def test_translate_without_correction(monkeypatch):
    fake = _use_fake(monkeypatch)
    segs = [Segment(1, 0, 1, "Hello there")]
    translator.translate(segs, source_lang="en", reflect=False, correct=False)
    assert segs[0].zh == "zh直译:Hello there" and segs[0].en == "Hello there"
    assert [FakeClient.step(p) for p in fake.prompts] == ["summary", "direct"]


def test_translate_chinese_source_proofreads_zh(monkeypatch):
    _use_fake(monkeypatch)
    segs = [Segment(1, 0, 1, "今天天汽很好")]
    translator.translate(segs, source_lang="zh")
    assert segs[0].zh == "校:今天天汽很好" and segs[0].en == "en意译:校:今天天汽很好"


def test_translate_zh_target_only_proofreads(monkeypatch):
    fake = _use_fake(monkeypatch)
    segs = [Segment(1, 0, 1, "今天天气很好")]
    translator.translate(segs, target="zh", source_lang="zh")
    assert segs[0].zh == "校:今天天气很好" and segs[0].en == ""
    assert [FakeClient.step(p) for p in fake.prompts] == ["summary", "correct"]


def test_translate_retries_and_falls_back(monkeypatch):
    fake = _use_fake(monkeypatch, drop_first="direct", fail={"free"})
    segs = [Segment(i, i, i + 1, f"line{i}") for i in range(1, 4)]
    translator.translate(segs, source_lang="en")
    assert [s.zh for s in segs] == ["zh直译:校:line1", "zh直译:校:line2", "zh直译:校:line3"]  # polish failed -> direct kept
    assert [FakeClient.step(p) for p in fake.prompts].count("direct") == 2  # incomplete answer retried


def test_custom_terms_override_extracted(monkeypatch):
    _use_fake(monkeypatch)
    info = translator.translate([Segment(1, 0, 1, "line1")], source_lang="en",
                                custom_terms=translator.parse_terms("LINE1=一号线"))
    assert [(t["src"], t["zh"]) for t in info["terms"]] == [("LINE1", "一号线")]  # extracted "line1" dropped


def test_custom_terms():
    assert translator.parse_terms("DeepSeek=深度求索\n\nfoo ＝ 福\nno separator") == [
        {"src": "DeepSeek", "zh": "深度求索", "en": "深度求索", "note": "用户指定"},
        {"src": "foo", "zh": "福", "en": "福", "note": "用户指定"},
    ]


def test_segmenter_rebuilds_sentences():
    from app.segmenter import Word, regroup

    words = [Word(0.0, 0.4, " Hello"), Word(0.4, 0.8, " world."), Word(0.9, 1.2, " How"),
             Word(1.2, 1.5, " are"), Word(1.5, 1.8, " you"), Word(3.5, 3.9, " fine")]
    segs = regroup(words)
    assert [(s.text, s.start, s.end) for s in segs] == [
        ("Hello world.", 0.0, 0.8), ("How are you", 0.9, 1.8), ("fine", 3.5, 3.9),  # pause ends a sentence
    ]


def test_segmenter_splits_long_sentences_at_commas():
    from app.segmenter import MAX_LEN, Word, regroup, text_len

    text = "我们今天要讲的内容非常多，包括语音识别、机器翻译还有字幕烧录，最后再讲一讲怎么部署到服务器上面去。"
    words = [Word(i * 0.2, i * 0.2 + 0.2, ch) for i, ch in enumerate(text)]
    segs = regroup(words)
    assert len(segs) > 1 and "".join(s.text for s in segs) == text
    assert all(text_len(s.text) <= MAX_LEN for s in segs)
    assert segs[0].text.endswith("，")  # split after a comma, not mid-phrase
    assert segs[0].end <= segs[1].start


def test_full_job_flow(monkeypatch, tmp_path):
    monkeypatch.setattr(main, "UPLOAD_DIR", tmp_path)
    monkeypatch.setattr(
        main.transcriber, "transcribe",
        lambda path, language=None, on_progress=None, **kw: ([Segment(1, 0, 2, "Hello")], "en"),
    )

    def fake_translate(segments, api_key=None, on_progress=None, **kw):
        segments[0].zh, segments[0].en = "你好", "Hello"
        return {"theme": "问候", "terms": []}

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
    assert job["status"] == "done", job.get("error")

    r = client.put(f"/api/jobs/{job_id}/segments", json={"segments": [{"id": 1, "zh": "您好"}]})
    assert r.status_code == 200
    srt = client.get(f"/api/jobs/{job_id}/subtitle.srt?download=true")
    assert "您好\nHello" in srt.text
    assert "attachment" in srt.headers["content-disposition"]
    assert client.get(f"/api/jobs/{job_id}/media").content == b"fake-audio"
    assert "DeepSeek 字幕工坊" in client.get("/").text


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
        seg = SimpleNamespace(start=0.0, end=1.0, text=" hi ", words=None)
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

    def fake_transcribe(path, language=None, on_progress=None, **kw):
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
    assert job["status"] == "done", job.get("error")
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
                        lambda path, language=None, on_progress=None, **kw: ([Segment(1, 0, 1, "hi")], "en"))
    monkeypatch.setattr(main.translator, "translate", lambda segments, **kw: {"theme": "", "terms": []})

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


def _audio_frame(pts, rate=48000, samples=1024):
    from fractions import Fraction

    import av
    import numpy as np

    f = av.AudioFrame.from_ndarray(np.zeros((1, samples), dtype=np.float32), format="fltp", layout="mono")
    f.sample_rate, f.pts, f.time_base = rate, pts, Fraction(1, rate)
    return f


def test_audio_track_survives_backward_timestamps(tmp_path):
    """Source audio whose timestamps step back used to fail with 'Invalid argument ... 22'."""
    import av

    from app.media import AudioTrack

    out_path = tmp_path / "a.mp4"
    with av.open(str(out_path), "w", format="mp4") as out:
        track = AudioTrack(out, "aac", 48000, 128000)
        for pts in (0, 1024, 2048, 1024, 3072):
            track.add(_audio_frame(pts))
        track.add(None)
    with av.open(str(out_path)) as c:
        assert c.streams.audio[0].frames > 0 or c.duration


def test_audio_track_keeps_late_start(tmp_path):
    import av

    from app.media import AudioTrack

    out_path = tmp_path / "late.mp4"
    with av.open(str(out_path), "w", format="mp4") as out:
        track = AudioTrack(out, "aac", 48000, 128000)
        for i in range(20):
            track.add(_audio_frame(72000 + i * 1024))  # audio starts at 1.5 s
        track.add(None)
    with av.open(str(out_path)) as c:
        first = next(p for p in c.demux(audio=0) if p.pts is not None)
        assert abs(float(first.pts * first.time_base) - 1.5) < 0.1


def test_netflix_timing():
    from app.timing import netflix_timing

    segs = [
        Segment(1, 1.0, 1.3, "Hi", zh="你好", en="Hi"),  # too short -> at least 5/6 s
        Segment(2, 2.4, 3.0, "x", zh="这一句比较长需要更多的时间来阅读完整内容", en="A longer line that needs more time"),
        Segment(3, 6.0, 7.0, "y", zh="下一句", en="Next"),
        Segment(4, 7.2, 8.0, "z", zh="最后", en="Last"),
    ]
    out = netflix_timing(segs, fps=25)
    frame = 1 / 25
    assert out[0].end == round(1.0 + 1.0, 3) or out[0].end - out[0].start >= 5 / 6 - 1e-9
    assert out[0].end <= out[1].start - 2 * frame + 1e-9  # 2-frame gap kept
    # reading speed: 20 Chinese characters at 9 cps need about 2.2 s
    assert out[1].end - out[1].start >= 20 / 9 - frame
    # gap under 0.5 s is closed to exactly 2 frames
    assert abs(out[2].end - (out[3].start - 2 * frame)) < 1e-6
    for s in out:  # on the frame grid
        assert abs(s.start * 25 - round(s.start * 25)) < 1e-6 and abs(s.end * 25 - round(s.end * 25)) < 1e-6


def test_netflix_timing_never_overlaps_or_exceeds_7s():
    from app.timing import MAX_DURATION, netflix_timing

    segs = [Segment(i, i * 0.9, i * 0.9 + 0.5, "w", zh="字" * 40) for i in range(1, 6)]
    segs.append(Segment(9, 20.0, 20.5, "w", zh="字" * 200))
    out = netflix_timing(segs, fps=24)
    for a, b in zip(out, out[1:]):
        assert a.end < b.start
    assert out[-1].end - out[-1].start <= MAX_DURATION + 1e-6


def test_netflix_line_wrap():
    from app.timing import wrap

    assert wrap("Short line") == ["Short line"]
    top, bottom = wrap("This subtitle line is clearly much longer than forty-two characters")
    assert len(top) <= 42 and len(bottom) <= 42 and len(top) <= len(bottom) + 6
    assert wrap("我们今天要讲的内容非常多，包括语音识别和机器翻译") == ["我们今天要讲的内容非常多，", "包括语音识别和机器翻译"]
    srt = to_srt([Segment(1, 0, 2, "x", zh="我们今天要讲的内容非常多，包括语音识别和机器翻译", en="e")], "zh")
    assert "非常多，\n包括" in srt  # single-language files are wrapped
    assert "非常多，包括" in to_srt([Segment(1, 0, 2, "x", zh="我们今天要讲的内容非常多，包括语音识别和机器翻译", en="e")])


def test_transcribe_options(monkeypatch):
    transcriber = _patch_whisper(monkeypatch)
    seen = {}
    original = FakeWhisper.transcribe

    def spy(self, audio, **kwargs):
        seen.update(kwargs)
        return original(self, audio, **kwargs)

    monkeypatch.setattr(FakeWhisper, "transcribe", spy)
    transcriber.transcribe("x.mp3", model="large-v3-turbo", hotwords=["DeepSeek", "VideoLingo"])
    assert seen["hotwords"] == "DeepSeek VideoLingo" and seen["condition_on_previous_text"] is False
    assert seen["word_timestamps"] is True
    assert FakeWhisper.created[-1] == ("cpu", "int8")


def _fake_tts(monkeypatch, calls=None):
    """edge-tts stand-in: a 440 Hz tone, 0.25 s per character, shorter at a faster rate."""
    import math
    import struct
    import wave

    from app import dub

    def synthesize(text, voice, path, rate=0, pitch="+0Hz"):
        if calls is not None:
            calls.append((text, voice, rate))
        seconds = len(text) * 0.25 / (1 + rate / 100)
        n = int(seconds * 24000)
        with wave.open(path, "wb") as w:  # decode_audio sniffs the format, the .mp3 name doesn't matter
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(24000)
            w.writeframes(b"".join(struct.pack("<h", int(12000 * math.sin(2 * math.pi * 440 * i / 24000))) for i in range(n)))

    monkeypatch.setattr(dub, "synthesize", synthesize)
    return dub


def test_dub_video_keeps_picture_and_fits_speech(monkeypatch, tmp_path):
    import av
    import numpy as np

    calls = []
    dub = _fake_tts(monkeypatch, calls)
    src = _make_media(tmp_path, [
        "-f", "lavfi", "-i", "color=c=blue:s=320x240:d=6:r=10",
        "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo", "-t", "6", "-shortest", "-pix_fmt", "yuv420p",
    ], "in.mp4")
    segs = [
        Segment(1, 0.5, 2.0, "a", zh="你好"),  # 0.5 s of speech, fits
        Segment(2, 3.0, 4.0, "b", zh="这一句非常非常长需要加快语速"),  # 3.5 s of speech for a 1.95 s slot
        Segment(3, 5.0, 5.5, "c", zh="好"),
    ]
    out, _ = dub.dub(str(src), tmp_path / "job_dubbed", segs, "zh", "zh-CN-XiaoxiaoNeural", bg_volume=0.2)
    assert out.suffix == ".mp4"
    rates = [rate for text, _, rate in calls if text.startswith("这一句")]
    assert rates == [0, dub.MAX_SPEEDUP]  # re-synthesised at the fastest allowed rate
    with av.open(str(out)) as c, av.open(str(src)) as s:
        assert c.streams.video[0].codec_context.name == "h264"
        assert c.streams.video[0].frames == s.streams.video[0].frames  # picture copied, not re-encoded
        assert c.streams.audio
        assert abs(float(c.duration / av.time_base) - 6.0) < 0.2  # sound no longer than the video
    from app.transcriber import decode_audio

    audio = decode_audio(str(out), 16000)
    loud = lambda a, b: float(np.abs(audio[int(a * 16000):int(b * 16000)]).mean())  # noqa: E731
    assert loud(0.6, 0.9) > 0.03 and loud(0.1, 0.4) < 0.005  # speech starts with the subtitle
    assert loud(4.5, 4.8) > 0.03 and loud(4.96, 4.995) < 0.005  # still too long: cut before the next line
    assert loud(5.05, 5.2) > 0.03  # next line starts on time


def test_dub_audio_only_gives_mp3_and_burn_gives_video(monkeypatch, tmp_path):
    import av

    dub = _fake_tts(monkeypatch)
    src = _make_media(tmp_path, ["-f", "lavfi", "-i", "sine=duration=3"], "in.mp3")
    segs = [Segment(1, 0.5, 2.0, "Hello", zh="你好", en="Hello")]
    out, _ = dub.dub(str(src), tmp_path / "a_dubbed", segs, "en", "en-US-AriaNeural")
    assert out.suffix == ".mp3"
    out, _ = dub.dub(str(src), tmp_path / "b_dubbed", segs, "zh", "zh-CN-XiaoxiaoNeural", burn_mode="bilingual")
    with av.open(str(out)) as c:
        assert out.suffix == ".mp4" and c.streams.video and c.streams.audio


def test_dub_api(monkeypatch, tmp_path):
    monkeypatch.setattr(main, "UPLOAD_DIR", tmp_path)
    monkeypatch.setattr(main, "EXPORT_DIR", tmp_path / "exports")
    monkeypatch.setattr(main.transcriber, "transcribe",
                        lambda path, language=None, on_progress=None, **kw: ([Segment(1, 0, 1, "hi")], "en"))
    monkeypatch.setattr(main.translator, "translate", lambda segments, **kw: {"theme": "", "terms": []})
    seen = {}

    def fake_dub(src, stem, segments, lang, voice, bg_volume, burn_mode, on_progress=None, clone=False):
        seen.update(lang=lang, voice=voice, bg=bg_volume, burn=burn_mode, clone=clone)
        out = Path(str(stem) + ".mp4")
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(b"dubbed")
        return out, [{"speaker": "说话人 1（男声）", "voice": "云健"}]

    monkeypatch.setattr(main, "dub", fake_dub)
    client = TestClient(main.app)
    assert "zh-CN-XiaoxiaoNeural" in str(client.get("/api/voices").json())
    job_id = client.post("/api/jobs", files={"file": ("a.mp4", b"x")}, data={"api_key": "k"}).json()["id"]
    for _ in range(50):
        if client.get(f"/api/jobs/{job_id}").json()["status"] == "done":
            break
        time.sleep(0.05)
    assert client.post(f"/api/jobs/{job_id}/dub", json={"lang": "fr"}).status_code == 400
    assert client.post(f"/api/jobs/{job_id}/dub", json={"lang": "zh", "voice": "nope"}).status_code == 400
    assert client.post(f"/api/jobs/{job_id}/dub", json={"lang": "zh", "engine": "nope"}).status_code == 400
    r = client.post(f"/api/jobs/{job_id}/dub", json={"lang": "en", "bg_volume": 5, "burn_mode": "en"})
    assert r.status_code == 200
    for _ in range(50):
        job = client.get(f"/api/jobs/{job_id}").json()
        if job["dub"]["status"] == "done":
            break
        time.sleep(0.05)
    assert seen == {"lang": "en", "voice": None, "bg": 1.0, "burn": "en", "clone": True}  # cloned by default
    assert job["dub"]["speakers"][0]["voice"] == "云健"
    r = client.get(f"/api/jobs/{job_id}/dubbed")
    assert r.content == b"dubbed" and "a.en.dubbed.mp4" in r.headers["content-disposition"]


def test_translate_fields_for_images(monkeypatch):
    fake = _use_fake(monkeypatch)
    segs = [Segment(1, 0, 1, "Hello")]
    translator.translate(segs, source_lang="en", fields=("zh",))
    assert segs[0].zh == "zh意译:校:Hello" and segs[0].en == ""
    assert [FakeClient.step(p) for p in fake.prompts] == ["summary", "correct", "direct", "free"]
    segs = [Segment(1, 0, 1, "こんにちは")]
    translator.translate(segs, source_lang="ja", fields=("zh", "en"))
    assert segs[0].zh.startswith("zh意译") and segs[0].en.startswith("en意译")


def test_image_language_guess_and_text():
    from app.image_translate import guess_language, to_text

    assert guess_language(["Welcome", "営業時間は六時から"]) == "ja"  # kana -> Japanese
    assert guess_language(["안녕하세요"]) == "ko"
    assert guess_language(["欢迎光临本店"]) == "zh"
    assert guess_language(["Open daily"]) == "en"
    lines = [{"text": "Open", "zh": "营业", "en": "Open"}]
    assert to_text(lines, "bilingual") == "Open\n营业\n"
    assert to_text(lines, "zh") == "营业\n"


def _sign(tmp_path):
    import pytest
    from PIL import Image, ImageDraw, ImageFont

    from app.burn import find_font

    try:
        font = ImageFont.truetype(find_font(), 40)
    except RuntimeError:
        pytest.skip("no CJK font installed")
    img = Image.new("RGB", (700, 200), (245, 240, 230))
    ImageDraw.Draw(img).text((30, 30), "Fresh fruit juice", font=font, fill=(180, 20, 20))
    path = tmp_path / "sign.png"
    img.save(path)
    return path


def test_image_render_replaces_text_in_place(tmp_path):
    import numpy as np
    from PIL import Image

    from app.image_translate import render

    path = _sign(tmp_path)
    lines = [{"box": [[28, 28], [400, 28], [400, 80], [28, 80]], "text": "Fresh fruit juice", "zh": "鲜榨果汁", "en": ""}]
    render(str(path), lines, "zh", str(tmp_path / "zh.png"))
    out = np.asarray(Image.open(tmp_path / "zh.png").convert("RGB")).astype(int)
    src = np.asarray(Image.open(path).convert("RGB")).astype(int)
    assert not np.array_equal(out[28:80, 28:400], src[28:80, 28:400])  # text replaced
    reds = out[28:80, 28:400][(out[28:80, 28:400, 0] > 140) & (out[28:80, 28:400, 1] < 80)]
    assert len(reds) > 20  # drawn in the original (red) text colour
    assert np.array_equal(out[120:, :], src[120:, :])  # rest of the image untouched
    render(str(path), lines, "bilingual", str(tmp_path / "bi.png"))
    bi = np.asarray(Image.open(tmp_path / "bi.png").convert("RGB")).astype(int)
    assert np.array_equal(bi[28:80, 28:400], src[28:80, 28:400])  # original kept
    assert (bi[82:130, 28:200].mean(axis=2) < 80).mean() > 0.3  # dark label below it


def test_image_ocr_real_engine(tmp_path):
    import pytest

    pytest.importorskip("rapidocr")
    from app.image_translate import ocr

    lines = ocr(str(_sign(tmp_path)))
    assert lines and "fruit" in lines[0]["text"].lower()


def test_image_api(monkeypatch, tmp_path):
    from app import image_translate

    monkeypatch.setattr(main, "UPLOAD_DIR", tmp_path)
    monkeypatch.setattr(main, "EXPORT_DIR", tmp_path / "exports")
    monkeypatch.setattr(image_translate, "ocr", lambda path, lang="auto": [
        {"box": [[28, 28], [400, 28], [400, 80], [28, 80]], "text": "Fresh fruit juice", "score": 0.99}])
    _use_fake(monkeypatch)
    client = TestClient(main.app)
    sign = _sign(tmp_path)
    assert client.post("/api/images", files={"file": ("a.gif", b"x")}, data={"api_key": "k"}).status_code == 400
    assert client.post("/api/images", files={"file": ("a.png", b"x")}, data={"api_key": "k", "target": "x"}).status_code == 400
    image_id = client.post("/api/images", files={"file": ("menu.png", sign.read_bytes())},
                           data={"api_key": "k", "target": "zh"}).json()["id"]
    for _ in range(100):
        item = client.get(f"/api/images/{image_id}").json()
        if item["status"] in ("done", "error"):
            break
        time.sleep(0.05)
    assert item["status"] == "done", item.get("error")
    assert item["lines"][0]["zh"] == "zh意译:校:Fresh fruit juice" and item["language"] == "en"
    r = client.get(f"/api/images/{image_id}/translated.png?download=true")
    assert r.headers["content-type"] == "image/png" and "menu.translated.png" in r.headers["content-disposition"]
    assert client.get(f"/api/images/{image_id}/text.txt").text == "zh意译:校:Fresh fruit juice\n"
    assert client.get(f"/api/images/{image_id}/source").content == sign.read_bytes()


def test_pages_are_never_stale():
    client = TestClient(main.app)
    r = client.get("/")
    assert r.headers["cache-control"] == "no-store"
    assert 'href="style.css?v=' in r.text and 'src="app.js?v=' in r.text  # versioned assets
    assert client.get("/style.css").headers["cache-control"] == "no-cache"
    assert "cache-control" not in client.get("/api/config").headers


def test_batch_endpoints(monkeypatch, tmp_path):
    import io
    import zipfile

    monkeypatch.setattr(main, "UPLOAD_DIR", tmp_path)
    monkeypatch.setattr(main, "EXPORT_DIR", tmp_path / "exports")
    monkeypatch.setattr(main.transcriber, "transcribe",
                        lambda path, language=None, on_progress=None, **kw: ([Segment(1, 0, 1, "Hello")], "en"))

    def fake_translate(segments, **kw):
        segments[0].zh, segments[0].en = "你好", "Hello"
        return {"theme": "", "terms": []}

    monkeypatch.setattr(main.translator, "translate", fake_translate)
    burned = []
    monkeypatch.setattr(main, "burn", lambda src, dst, segs, mode, on_progress=None: (burned.append(mode), Path(dst).write_bytes(b"v")))
    client = TestClient(main.app)
    ids = [client.post("/api/jobs", files={"file": (name, b"x")}, data={"api_key": "k", **extra}).json()["id"]
           for name, extra in (("a.mp4", {}), ("a.mp4", {}), ("b.mp3", {"target": "zh"}))]
    for _ in range(100):
        brief = client.get(f"/api/jobs?ids={','.join(ids)},missing").json()
        if all(b["status"] == "done" for b in brief):
            break
        time.sleep(0.05)
    assert [b["id"] for b in brief] == ids and "segments" not in brief[0]
    r = client.post("/api/batch/subtitles.zip", json={"ids": ids, "mode": "en", "fmt": "srt"})
    names = sorted(zipfile.ZipFile(io.BytesIO(r.content)).namelist())
    assert names == ["a (2).en.srt", "a.en.srt", "b.zh.srt"]  # Chinese-only job falls back to zh, names deduplicated
    assert client.post("/api/batch/subtitles.zip", json={"ids": ["nope"]}).status_code == 409
    r = client.post("/api/batch/burn", json={"ids": ids, "mode": "bilingual"})
    assert sorted(r.json()["started"]) == sorted(ids)
    for _ in range(100):
        if all((b["burn"] or {}).get("status") == "done" for b in client.get(f"/api/jobs?ids={','.join(ids)}").json()):
            break
        time.sleep(0.05)
    assert sorted(burned) == ["bilingual", "bilingual", "zh"]


def _text_video(tmp_path):
    """4 s at 10 fps: "Welcome" the whole time, "Big Sale Today" from 1 s to 3 s."""
    import av
    import numpy as np
    import pytest
    from PIL import Image, ImageDraw, ImageFont

    from app.burn import find_font

    try:
        font = ImageFont.truetype(find_font(), 44)
    except RuntimeError:
        pytest.skip("no CJK font installed")
    path = tmp_path / "text.mp4"
    with av.open(str(path), "w") as out:
        stream = out.add_stream("libx264", rate=10)
        stream.width, stream.height, stream.pix_fmt = 640, 360, "yuv420p"
        for i in range(40):
            img = Image.new("RGB", (640, 360), (30, 60, 110))
            draw = ImageDraw.Draw(img)
            draw.text((40, 40), "Welcome", font=font, fill=(255, 255, 255))
            if 10 <= i < 30:
                draw.text((40, 200), "Big Sale Today", font=font, fill=(255, 210, 0))
            frame = av.VideoFrame.from_ndarray(np.asarray(img), format="rgb24")
            frame.pts = i
            out.mux(stream.encode(frame))
        out.mux(stream.encode(None))
    return path


def test_screen_text_scan_tracks_events(tmp_path):
    import pytest

    pytest.importorskip("rapidocr")
    from app import screen_text

    events, size = screen_text.scan(str(_text_video(tmp_path)), interval=0.5)
    assert size == [640, 360]
    by_text = {e["text"].lower(): e for e in events}
    welcome = next(e for t, e in by_text.items() if "welcome" in t)
    sale = next(e for t, e in by_text.items() if "sale" in t)
    assert welcome["start"] == 0 and welcome["end"] >= 3.5  # one event for the whole video
    assert 0.5 <= sale["start"] <= 1.0 and 2.9 <= sale["end"] <= 3.6  # appears and disappears on time
    assert min(p[1] for p in sale["box"]) > 150  # box in full-resolution coordinates
    assert len(events) == 2


def test_screen_srt():
    from app.screen_text import to_srt

    events = [{"start": 1.0, "end": 2.5, "text": "OPEN", "zh": "营业中", "en": "OPEN", "box": []}]
    assert to_srt(events, "zh") == "1\n00:00:01,000 --> 00:00:02,500\n{\\an8}营业中\n"
    assert to_srt(events, "bilingual").endswith("{\\an8}OPEN\n营业中\n")


def test_burn_replaces_on_screen_text(tmp_path):
    import numpy as np

    from app.burn import burn

    src = _text_video(tmp_path)
    out = tmp_path / "out.mp4"
    events = [{"id": 1, "start": 1.0, "end": 3.0, "box": [[38, 195], [400, 195], [400, 255], [38, 255]],
               "text": "Big Sale Today", "zh": "今日大促", "en": ""}]
    burn(str(src), str(out), [], "zh", screen={"events": events, "mode": "zh", "size": [640, 360]})
    before, during = _frame_at(src, 2.0), _frame_at(out, 2.0)
    box = (slice(200, 250), slice(40, 400))
    yellow = lambda f: ((f[box][..., 0] > 200) & (f[box][..., 1] > 150) & (f[box][..., 2] < 100)).sum()  # noqa: E731
    assert yellow(before) > 300 and yellow(during) > 50  # translation drawn in the original colour
    assert abs(before[box].astype(int) - during[box].astype(int)).mean() > 10  # original text replaced
    assert abs(_frame_at(src, 0.2)[box].astype(int) - _frame_at(out, 0.2)[box].astype(int)).mean() < 3  # not before 1 s


def test_screen_api_and_burn_options(monkeypatch, tmp_path):
    from app import screen_text

    monkeypatch.setattr(main, "UPLOAD_DIR", tmp_path)
    monkeypatch.setattr(main, "EXPORT_DIR", tmp_path / "exports")
    monkeypatch.setattr(main.transcriber, "transcribe", lambda path, language=None, on_progress=None, **kw: ([], None))
    monkeypatch.setattr(screen_text, "scan", lambda path, lang, interval, on_progress=None: (
        [{"id": 1, "start": 0.0, "end": 2.0, "box": [[0, 0], [10, 0], [10, 10], [0, 10]], "text": "OPEN", "score": 0.9}], [640, 360]))
    _use_fake(monkeypatch)
    calls = []
    monkeypatch.setattr(main, "burn", lambda src, dst, segs, mode, on_progress=None, screen=None: (
        calls.append((segs, mode, screen)), Path(dst).write_bytes(b"v")))
    client = TestClient(main.app)
    job_id = client.post("/api/jobs", files={"file": ("a.mp4", b"x")}, data={"api_key": "k"}).json()["id"]

    def wait(pred):
        for _ in range(100):
            job = client.get(f"/api/jobs/{job_id}").json()
            if pred(job):
                return job
            time.sleep(0.05)
        raise AssertionError(job)

    job = wait(lambda j: j["status"] in ("done", "error"))
    assert job["status"] == "done" and job["segments"] == []  # no speech is not an error any more
    assert client.post(f"/api/jobs/{job_id}/burn", json={"mode": "zh"}).status_code == 409  # nothing to burn yet
    assert client.post(f"/api/jobs/{job_id}/burn", json={"mode": "zh", "screen": "replace"}).status_code == 409
    assert client.post(f"/api/jobs/{job_id}/screen", json={"interval": 3}).status_code == 400
    assert client.post(f"/api/jobs/{job_id}/screen", json={"target": "zh", "api_key": "k"}).status_code == 200
    job = wait(lambda j: (j["screen"] or {}).get("status") in ("done", "error"))
    assert job["screen"]["status"] == "done", job["screen"]
    assert job["screen"]["events"][0]["zh"] == "zh意译:校:OPEN"
    assert "{\\an8}zh意译:校:OPEN" in client.get(f"/api/jobs/{job_id}/screen.srt").text
    r = client.post(f"/api/jobs/{job_id}/burn", json={"mode": "zh", "screen": "replace", "subtitles": False})
    assert r.status_code == 200
    wait(lambda j: (j["burn"] or {}).get("status") == "done")
    segs, mode, screen = calls[-1]
    assert segs == [] and screen["mode"] == "zh" and screen["size"] == [640, 360]


def test_queue_position_and_cancel(monkeypatch, tmp_path):
    import threading

    monkeypatch.setattr(main, "UPLOAD_DIR", tmp_path)
    monkeypatch.setattr(main.transcriber, "model_cached", lambda name=None: True)
    release = threading.Event()

    def slow_transcribe(path, language=None, on_progress=None, **kw):
        while not release.is_set():  # a long job that reports progress, like Whisper
            on_progress(0.5)
            time.sleep(0.02)
        return [Segment(1, 0, 1, "hi")], "en"

    monkeypatch.setattr(main.transcriber, "transcribe", slow_transcribe)
    monkeypatch.setattr(main.translator, "translate", lambda segments, **kw: {"theme": "", "terms": []})
    client = TestClient(main.app)
    a, b, c = (client.post("/api/jobs", files={"file": (f"{n}.mp4", b"x")}, data={"api_key": "k"}).json()["id"]
               for n in "abc")
    for _ in range(50):
        if client.get(f"/api/jobs/{a}").json()["status"] == "transcribing":
            break
        time.sleep(0.02)
    job_c = client.get(f"/api/jobs/{c}").json()
    assert job_c["status"] == "queued" and job_c["ahead"] == 2
    assert "前面还有 2 个任务" in job_c["stage"] and "a.mp4" in job_c["stage"]

    assert client.post(f"/api/jobs/{b}/cancel").json() == {"ok": True}  # waiting: cancelled at once
    assert client.get(f"/api/jobs/{b}").json()["status"] == "cancelled"
    assert client.get(f"/api/jobs/{c}").json()["ahead"] == 1

    assert client.post(f"/api/jobs/{a}/cancel").status_code == 200  # running: stops at next progress update
    for _ in range(100):
        if client.get(f"/api/jobs/{a}").json()["status"] == "cancelled":
            break
        time.sleep(0.02)
    assert client.get(f"/api/jobs/{a}").json()["status"] == "cancelled"
    release.set()
    for _ in range(100):
        if client.get(f"/api/jobs/{c}").json()["status"] == "done":
            break
        time.sleep(0.02)
    assert client.get(f"/api/jobs/{c}").json()["status"] == "done"  # the queue moves on
    assert client.post(f"/api/jobs/{c}/cancel").status_code == 409


def test_model_download_is_announced(monkeypatch, tmp_path):
    monkeypatch.setattr(main, "UPLOAD_DIR", tmp_path)
    monkeypatch.setattr(main.transcriber, "model_cached", lambda name=None: False)
    stages = []

    def transcribe(path, language=None, on_progress=None, **kw):
        stages.append(next(j["stage"] for j in main.jobs.values() if j["media_path"] == path))
        return [], None

    monkeypatch.setattr(main.transcriber, "transcribe", transcribe)
    client = TestClient(main.app)
    job_id = client.post("/api/jobs", files={"file": ("a.mp4", b"x")}, data={"api_key": "k", "model": "large-v3-turbo"}).json()["id"]
    for _ in range(100):
        if client.get(f"/api/jobs/{job_id}").json()["status"] == "done":
            break
        time.sleep(0.02)
    assert stages, {k: v for k, v in client.get(f"/api/jobs/{job_id}").json().items() if k in ("status", "stage", "error")}
    assert "正在下载识别模型 large-v3-turbo" in stages[0] and "1.6 GB" in stages[0]


def test_segmenter_unglues_punctuation_and_merges_orphans():
    from app.segmenter import Word, regroup

    def words(tokens, gaps=None):
        out, t = [], 0.0
        for i, tok in enumerate(tokens):
            t += (gaps or {}).get(i, 0)
            out.append(Word(t, t + 0.25, tok))
            t += 0.25
        return out

    # Whisper glued the period to the next sentence's first character
    segs = regroup(words(["我们", "今天", "去", "公园", "散步", "了", "。我", "们", "明天", "再", "来", "。"]))
    assert [s.text for s in segs] == ["我们今天去公园散步了。", "我们明天再来。"]
    assert segs[0].end <= segs[1].start
    assert [s.text for s in regroup(words([" It", " was", " fun", ".I", " will", " come", " back", "."]))] == [
        "It was fun.", "I will come back."]
    # a pause before the last character must not leave it on its own
    assert [s.text for s in regroup(words(["这个", "问题", "我们", "下次", "再", "讨论", "吧", "。"], {6: 1.5}))] == [
        "这个问题我们下次再讨论吧。"]
    # but a complete one-character sentence stays
    assert [s.text for s in regroup(words(["你", "吃", "饭", "了", "吗", "？", "好", "。", "走", "吧", "。"], {6: 1.5, 8: 1.5}))] == [
        "你吃饭了吗？", "好。", "走吧。"]


def _voices_audio():
    """16 kHz: a low voice (110 Hz) at 0-2 s and 6-8 s, a high voice (230 Hz) at 3-5 s."""
    import numpy as np

    sr = 16000
    audio = np.zeros(9 * sr, dtype=np.float32)
    t = np.arange(2 * sr) / sr
    for start, f0, amp in ((0, 110, 0.3), (3, 230, 0.3), (6, 112, 0.6)):
        tone = sum(np.sin(2 * np.pi * f0 * k * t) / k for k in (1, 2, 3)) * amp / 2
        audio[start * sr: start * sr + len(t)] = tone
    return audio


def test_speaker_analysis_and_auto_voices():
    from app import voices

    segs = [Segment(1, 0, 2, "a"), Segment(2, 3, 5, "b"), Segment(3, 6, 8, "c"), Segment(4, 8.2, 8.9, "d")]
    found = voices.analyze_speakers(_voices_audio(), segs)
    labels, speakers = found["labels"], found["speakers"]
    assert labels[1] == labels[3] != labels[2]  # same low voice twice, a different high voice
    assert labels[4] == labels[3]  # silent line goes to the speaker before it
    low = next(s for s in speakers if s["id"] == labels[1])
    high = next(s for s in speakers if s["id"] == labels[2])
    assert low["gender"] == "male" and abs(low["f0"] - 111) < 5
    assert high["gender"] == "female" and abs(high["f0"] - 230) < 8
    assert found["loudness"][3] > found["loudness"][1] * 1.5  # line 3 is louder
    chosen = voices.auto_voices(speakers, "zh")
    assert chosen[low["id"]]["voice"] in {v for v, _, _ in voices.EDGE_VOICES["zh"]["male"]}
    assert chosen[high["id"]]["voice"] in {v for v, _, _ in voices.EDGE_VOICES["zh"]["female"]}
    voice, base, _ = next(v for v in voices.EDGE_VOICES["zh"]["female"] if v[0] == chosen[high["id"]]["voice"])
    assert voice == "zh-CN-XiaoyiNeural"  # 230 Hz is closest to the brighter female voice (240 Hz)
    assert chosen[high["id"]]["pitch"] == f"{round(high['f0'] - base):+d}Hz"  # shifted to the speaker's pitch
    two_men = voices.auto_voices([{"id": 0, "f0": 100, "gender": "male", "lines": 3},
                                  {"id": 1, "f0": 140, "gender": "male", "lines": 2}], "en")
    assert two_men[0]["voice"] != two_men[1]["voice"]  # different people, different voices


def test_dub_matches_voices_to_speakers(monkeypatch, tmp_path):
    import wave

    import numpy as np

    calls = []
    dub = _fake_tts(monkeypatch, calls)
    path = tmp_path / "talk.wav"
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes((np.clip(_voices_audio(), -1, 1) * 32767).astype("<i2").tobytes())
    segs = [Segment(1, 0, 2, "a", zh="你好"), Segment(2, 3, 5, "b", zh="早上好"), Segment(3, 6, 8, "c", zh="再见")]
    out, speakers = dub.dub(str(path), tmp_path / "x_dubbed", segs, "zh", bg_volume=0)
    used = {text: voice for text, voice, _ in calls}
    assert used["你好"] == used["再见"] != used["早上好"]
    assert len(speakers) == 2 and all("说话人" in s["speaker"] for s in speakers)


def test_pitch_has_no_octave_errors():
    import numpy as np

    from app.voices import pitch

    t = np.arange(16000) / 16000
    for f0 in (100, 180, 260, 350):
        assert abs(pitch((0.3 * np.sin(2 * np.pi * f0 * t)).astype(np.float32)) - f0) < f0 * 0.04
    assert pitch(np.zeros(16000, dtype=np.float32)) is None


def test_screen_text_runs_automatically_after_subtitles(monkeypatch, tmp_path):
    from app import screen_text

    monkeypatch.setattr(main, "UPLOAD_DIR", tmp_path)
    monkeypatch.setattr(main.transcriber, "model_cached", lambda name=None: True)
    monkeypatch.setattr(main.transcriber, "transcribe",
                        lambda path, language=None, on_progress=None, **kw: ([Segment(1, 0, 1, "안녕")], "ko"))
    monkeypatch.setattr(screen_text, "has_picture", lambda path: True)
    seen = {}

    def scan(path, lang, interval, on_progress=None):
        seen["lang"] = lang
        return [{"id": 1, "start": 0.0, "end": 1.0, "box": [[0, 0], [9, 0], [9, 9], [0, 9]], "text": "출구", "score": 0.9}], [640, 360]

    monkeypatch.setattr(screen_text, "scan", scan)
    _use_fake(monkeypatch)
    client = TestClient(main.app)
    job_id = client.post("/api/jobs", files={"file": ("a.mp4", b"x")}, data={"api_key": "k", "screen": "1"}).json()["id"]
    for _ in range(100):
        job = client.get(f"/api/jobs/{job_id}").json()
        if job["status"] == "done" and (job["screen"] or {}).get("status") == "done":
            break
        time.sleep(0.05)
    assert job["screen"]["status"] == "done" and job["screen"]["target"] == "orig_zh"
    assert seen["lang"] == "ko"  # OCR model follows the spoken language
    assert job["screen"]["events"][0]["zh"]


def test_voiceprints_separate_speakers_with_the_same_pitch(monkeypatch):
    import numpy as np

    from app import speakers, voices

    sr = 16000
    audio = np.zeros(9 * sr, dtype=np.float32)
    t = np.arange(2 * sr) / sr
    for start, amp in ((0, 0.2), (3, 0.4), (6, 0.2)):  # same 120 Hz pitch, two "people"
        audio[start * sr: start * sr + len(t)] = amp * np.sin(2 * np.pi * 120 * t)
    # stand-in voiceprints: person A speaks softly, person B loudly
    monkeypatch.setattr(speakers, "available", lambda: True)
    monkeypatch.setattr(speakers, "embed", lambda x: np.array([1.0, 0, 0]) if np.abs(x).max() < 0.3 else np.array([0, 1.0, 0]))
    segs = [Segment(1, 0, 2, "你好啊"), Segment(2, 3, 5, "我很好"), Segment(3, 6, 8, "那就好"), Segment(4, 8.1, 8.5, "嗯")]
    found = voices.analyze_speakers(audio, segs)
    assert found["method"] == "voiceprint"
    assert found["labels"][1] == found["labels"][3] != found["labels"][2]
    assert found["labels"][4] == found["labels"][3]  # too short for a voiceprint: the closest line's speaker
    chosen = voices.auto_voices(found["speakers"], "zh")
    a, b = (chosen[found["labels"][i]] for i in (1, 2))
    assert a["voice"] != b["voice"]  # two men, two different voices
    assert voices.describe(found["speakers"], chosen)[0]["speaker"].startswith("说话人 1（男声")


def test_voiceprint_clustering():
    import numpy as np

    from app.speakers import cluster

    rng = np.random.default_rng(1)
    centers = [rng.standard_normal(192) for _ in range(3)]
    vectors = {}
    for i in range(12):
        v = centers[i % 3] + 0.3 * rng.standard_normal(192)
        vectors[i] = v / np.linalg.norm(v)
    groups = cluster(vectors)
    assert sorted(sorted(g) for g in groups) == [[0, 3, 6, 9], [1, 4, 7, 10], [2, 5, 8, 11]]
    assert len(cluster(vectors, max_speakers=2)) == 2


def test_fbank_matches_kaldi():
    import numpy as np
    import pytest

    knf = pytest.importorskip("kaldi_native_fbank")
    from app.speakers import fbank

    rng = np.random.default_rng(0)
    x = (0.1 * rng.standard_normal(16000) + 0.3 * np.sin(2 * np.pi * 220 * np.arange(16000) / 16000)).astype(np.float32)
    opts = knf.FbankOptions()
    opts.frame_opts.dither = 0
    opts.mel_opts.num_bins = 80
    ref = knf.OnlineFbank(opts)
    ref.accept_waveform(16000, x.tolist())
    ref.input_finished()
    expected = np.array([ref.get_frame(i) for i in range(ref.num_frames_ready)])
    assert np.abs(fbank(x) - expected).max() < 1e-2


def test_line_voices_follow_pitch_and_speed():
    from app import voices

    assert voices.syllables("你好，世界") == 4 and voices.syllables("Hello there, friend") == 5
    found = {"speakers": [{"id": 0, "f0": 120.0, "gender": "male", "lines": 3, "speed": 6.3}],
             "labels": {1: 0, 2: 0, 3: 0}, "pitch": {1: 120.0, 2: 150.0, 3: None}}
    chosen = voices.auto_voices(found["speakers"], "zh")
    assert chosen[0]["rate"] == 20  # a fast talker gets a faster voice (capped)
    lines = voices.line_voices(found, chosen)
    shift = chosen[0]["shift"]
    assert lines[1]["pitch"] == f"{shift:+d}Hz" and lines[3]["pitch"] == f"{shift:+d}Hz"
    assert int(lines[2]["pitch"][:-2]) > shift + 10  # said higher than usual: dubbed higher
    assert {v["rate"] for v in lines.values()} == {20}
    assert voices._rate_for(1.0) == -10  # slow talkers: only a little slower


def test_dub_clones_the_speakers_voices(monkeypatch, tmp_path):
    import wave

    import numpy as np

    from app import clone

    calls = []
    dub = _fake_tts(monkeypatch, calls)
    path = tmp_path / "talk.wav"
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes((np.clip(_voices_audio(), -1, 1) * 32767).astype("<i2").tobytes())
    spoken = []

    def speak(ref, text, out, speed=1.0):
        spoken.append((ref["text"], len(ref["samples"]), text))
        with wave.open(out, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(24000)
            w.writeframes(b"\x10\x00" * int(len(text) * 0.2 * 24000))

    monkeypatch.setattr(clone, "prepare", lambda: None)
    monkeypatch.setattr(clone, "speak", speak)
    segs = [Segment(1, 0, 2, "low one", zh="你好"), Segment(2, 3, 5, "high", zh="早上好"), Segment(3, 6, 8, "low two", zh="再见")]
    out, speakers = dub.dub(str(path), tmp_path / "x_dubbed", segs, "zh", bg_volume=0, clone=True)
    assert not calls  # no edge-tts voice was used
    refs = {text: ref for ref, _, text in spoken}
    assert refs["你好"] == refs["再见"] == "low one low two"  # each speaker's own lines (and transcript) as reference
    assert refs["早上好"] == "high"
    assert all(n == 16000 * 4 + int(clone.GAP * 16000) or n == 16000 * 2 for _, n, t in spoken)
    assert all(s["voice"].startswith("克隆原声") for s in speakers)


def test_clone_reference_picks_clear_lines():
    import numpy as np

    from app import clone

    audio = np.zeros(40 * 16000, dtype=np.float32)
    segs = [Segment(i, i * 4.0, i * 4.0 + 3.0, f"line{i}") for i in range(8)] + [Segment(9, 33, 33.5, "嗯")]
    loud = {i: 0.1 * (i + 1) for i in range(8)}
    ref = clone.reference(audio, segs, [s.id for s in segs], loud)
    assert ref["text"] == "line5 line6 line7"  # the loudest lines, in order, about 8 s
    assert abs(len(ref["samples"]) / 16000 - (9 + 2 * clone.GAP)) < 0.01
    assert clone.reference(audio, segs, [], loud) is None


def test_clone_download_failure_reaches_the_page(monkeypatch, tmp_path):
    import pytest

    from app import clone

    dub = _fake_tts(monkeypatch)

    def prepare():
        raise clone.CloneError("下载声音克隆模型失败：github: timed out")

    monkeypatch.setattr(clone, "prepare", prepare)
    src = _make_media(tmp_path, ["-f", "lavfi", "-i", "sine=frequency=150:duration=3", "-c:a", "aac"], "a.m4a")
    with pytest.raises(dub.DubError, match="下载声音克隆模型失败.*微软神经语音"):
        dub.dub(src, tmp_path / "y", [Segment(1, 0, 2.5, "hi", zh="你好")], "zh", clone=True)


def test_unexpected_errors_show_their_reason(monkeypatch, tmp_path):
    blocker = tmp_path / "uploads"
    blocker.write_text("a file where the upload folder should be")
    monkeypatch.setattr(main, "UPLOAD_DIR", blocker)
    monkeypatch.setattr(main, "ERROR_LOG", tmp_path / "error.log")
    client = TestClient(main.app, raise_server_exceptions=False)
    r = client.post("/api/jobs", files={"file": ("a.mp3", b"x")}, data={"target": "zh"})
    assert r.status_code == 500
    assert "读写文件失败" in r.json()["detail"]
    assert "Traceback" in (tmp_path / "error.log").read_text(encoding="utf-8")


def test_hallucinations_are_dropped(monkeypatch):
    from app.transcriber import is_hallucination

    transcriber = _patch_whisper(monkeypatch)

    def segs(self, audio, **kwargs):
        def s(start, text, nsp=0.01, lp=-0.2, cr=1.2):
            return SimpleNamespace(start=start, end=start + 1, text=text, words=None,
                                   no_speech_prob=nsp, avg_logprob=lp, compression_ratio=cr)
        return iter([
            s(0, "今天我们去公园。"),
            s(2, "请不吝点赞 订阅 转发 打赏支持明镜与点点栏目"),  # invented over music
            s(4, "嗯嗯嗯嗯嗯", nsp=0.9, lp=-0.8),  # not speech
            s(6, "好的。"), s(7, "好的。"), s(8, "好的。"), s(9, "好的。"),  # a loop
            s(11, "谢谢大家观看。"),  # said clearly: kept
        ]), SimpleNamespace(duration=12.0, language="zh")

    monkeypatch.setattr(FakeWhisper, "transcribe", segs)
    out, _ = transcriber.transcribe("x.mp3")
    text = "".join(seg.text for seg in out)
    assert "明镜" not in text and "嗯嗯" not in text
    assert text.count("好的") == 2 and "谢谢大家观看" in text and "公园" in text
    assert is_hallucination("Thanks for watching!", no_speech_prob=0.5)
    assert not is_hallucination("Thanks for watching!", no_speech_prob=0.01, avg_logprob=-0.2)
    assert is_hallucination("以下是普通话的句子，使用简体中文")


def test_dub_speaks_a_split_sentence_in_one_go_and_mutes_the_original(monkeypatch, tmp_path):
    import numpy as np

    from app import dub as dubmod

    voice = {"voice": "zh-CN-YunxiNeural", "pitch": "+0Hz", "rate": 0, "speaker": 0}
    other = {**voice, "voice": "zh-CN-XiaoxiaoNeural", "speaker": 1}
    segs = [Segment(1, 0.0, 1.5, "a", zh="我们今天"), Segment(2, 1.7, 3.0, "b", zh="去公园散步。"),
            Segment(3, 3.1, 4.0, "c", zh="好啊"), Segment(4, 6.0, 7.0, "d", zh="走吧")]
    parts = dubmod.utterances(segs, "zh", {1: voice, 2: voice, 3: other, 4: other})
    assert [p["text"] for p in parts] == ["我们今天，去公园散步。", "好啊", "走吧"]  # same speaker, short gap: one go
    assert parts[0]["lines"] == [1, 2] and parts[0]["end"] == 3.0

    rate = dubmod.RATE
    original = np.full(8 * rate, 0.5, dtype=np.float32)
    ducked = dubmod.duck(original, [(1.0, 2.0), (5.0, 6.0)], 0.0)
    assert ducked[int(1.5 * rate)] == 0 and ducked[int(5.5 * rate)] == 0  # muted while talking
    assert ducked[int(3.5 * rate)] == 0.5 and ducked[int(0.2 * rate)] == 0.5  # full volume in between
    assert 0 < ducked[int(0.95 * rate)] < 0.5  # faded, not cut
    assert dubmod.duck(original, [(1.0, 2.0)], 0.2)[int(1.5 * rate)] == np.float32(0.1)

    silent = np.concatenate([np.zeros(rate // 2), np.full(rate, 0.3), np.zeros(rate // 2)]).astype(np.float32)
    assert abs(len(dubmod._trim(silent)) - rate) < rate * 0.1  # TTS silence around speech is cut
