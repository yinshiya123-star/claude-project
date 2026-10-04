"""FastAPI server: upload media -> transcribe -> translate with DeepSeek -> bilingual subtitles."""

from __future__ import annotations

import os
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import quote

from dotenv import load_dotenv
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse, PlainTextResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

load_dotenv()

from . import transcriber, translator  # noqa: E402  (env must be loaded first)
from .burn import burn  # noqa: E402
from .dub import VOICES, dub  # noqa: E402
from . import image_translate  # noqa: E402
from .export import export_mp3  # noqa: E402
from .media import log_ffmpeg_errors, video_fps  # noqa: E402
from .timing import netflix_timing  # noqa: E402
from .subtitles import Segment, to_lrc, to_srt, to_vtt  # noqa: E402

BASE_DIR = Path(__file__).resolve().parent.parent
STATIC_DIR = BASE_DIR / "static"
UPLOAD_DIR = Path(os.getenv("UPLOAD_DIR", BASE_DIR / "data" / "uploads"))
UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
EXPORT_DIR = UPLOAD_DIR.parent / "exports"
MAX_UPLOAD_BYTES = int(os.getenv("MAX_UPLOAD_MB", "500")) * 1024 * 1024
ALLOWED_EXT = {".mp3", ".mp4", ".m4a", ".wav", ".flac", ".ogg", ".aac", ".webm", ".mov", ".mkv"}

app = FastAPI(title="DeepSeek 中英字幕生成器")
log_ffmpeg_errors()

# Whisper is CPU/GPU heavy: process one job at a time.
executor = ThreadPoolExecutor(max_workers=1)
jobs: dict[str, dict] = {}
jobs_lock = threading.Lock()
export_lock = threading.Lock()
# Burning re-encodes the whole video: run one at a time, apart from transcription.
burn_executor = ThreadPoolExecutor(max_workers=1)
MODES = ("bilingual", "zh", "en", "orig")
SUBTITLE_TYPES = {"srt": "application/x-subrip", "vtt": "text/vtt", "lrc": "text/plain"}


def _update(job_id: str, **fields) -> None:
    with jobs_lock:
        jobs[job_id].update(fields)


def _get_job(job_id: str) -> dict:
    with jobs_lock:
        job = jobs.get(job_id)
        if job is None:
            raise HTTPException(404, "任务不存在")
        return dict(job)


def _has_key(api_key: str | None) -> bool:
    return bool(api_key or os.getenv("DEEPSEEK_API_KEY"))


def _run_job(job_id: str, path: str, language: str | None, api_key: str | None, target: str,
             reflect: bool = True, terms_text: str = "", model: str | None = None, correct: bool = True) -> None:
    try:
        _update(job_id, status="transcribing", stage="语音识别中（首次使用某个模型时需要先下载）", progress=0.0)
        custom_terms = translator.parse_terms(terms_text)
        segments, lang = transcriber.transcribe(
            path,
            language=language,
            on_progress=lambda p: _update(job_id, progress=round(p * 0.6, 3), stage="语音识别中"),
            model=model,
            hotwords=[t["src"] for t in custom_terms],
        )
        _update(job_id, language=lang, segments=[s.to_dict() for s in segments])
        if not segments:
            raise RuntimeError("没有识别到任何语音")

        if target == "zh" and not _has_key(api_key):
            # Chinese-only without a key: use the recognised text as is.
            for seg in segments:
                seg.zh, seg.en = seg.text, ""
        else:
            stage = "DeepSeek 校正识别文本、翻译中" if correct else "DeepSeek 翻译中"
            if target == "zh":
                stage = "DeepSeek 校正识别文本中"
            elif reflect:
                stage += "（翻译→反思→润色）"
            _update(job_id, status="translating", stage="DeepSeek 总结主题、提取术语", progress=0.6)
            info = translator.translate(
                segments,
                api_key=api_key,
                on_progress=lambda p: _update(job_id, progress=round(0.6 + p * 0.4, 3),
                                              stage=stage if p > 0.1 else "DeepSeek 总结主题、提取术语"),
                target=target,
                source_lang=lang,
                reflect=reflect,
                custom_terms=custom_terms,
                correct=correct,
            )
            _update(job_id, theme=info["theme"], terms=info["terms"])

        # Netflix timing: reading speed, min/max duration, 2-frame gaps, frame grid.
        langs = ("zh",) if target == "zh" else ("zh", "en")
        segments = netflix_timing(segments, video_fps(path), langs)
        _update(
            job_id,
            status="done",
            stage="完成",
            progress=1.0,
            segments=[s.to_dict() for s in segments],
            finished_at=time.time(),
        )
    except Exception as e:
        _update(job_id, status="error", stage="出错", error=str(e))


@app.post("/api/jobs")
async def create_job(
    file: UploadFile = File(...),
    language: str = Form(""),
    api_key: str = Form(""),
    target: str = Form("bilingual"),
    reflect: str = Form("1"),
    terms: str = Form(""),
    model: str = Form(""),
    correct: str = Form("1"),
):
    if model and model not in transcriber.MODELS:
        raise HTTPException(400, f"model 只支持 {' / '.join(transcriber.MODELS)}")
    if target not in ("bilingual", "zh"):
        raise HTTPException(400, "target 只支持 bilingual / zh")
    if target == "zh":
        language = "zh"  # Chinese audio -> Chinese subtitles
    elif not _has_key(api_key.strip()):
        raise HTTPException(400, "请填写 DeepSeek API Key，或在服务端 .env 中配置 DEEPSEEK_API_KEY")
    ext = Path(file.filename or "").suffix.lower()
    if ext not in ALLOWED_EXT:
        raise HTTPException(400, f"不支持的文件格式 {ext or '(无扩展名)'}，支持: {', '.join(sorted(ALLOWED_EXT))}")

    job_id = uuid.uuid4().hex[:12]
    dest = UPLOAD_DIR / f"{job_id}{ext}"
    size = 0
    with dest.open("wb") as out:
        while chunk := await file.read(1024 * 1024):
            size += len(chunk)
            if size > MAX_UPLOAD_BYTES:
                out.close()
                dest.unlink(missing_ok=True)
                raise HTTPException(413, f"文件超过 {MAX_UPLOAD_BYTES // 1024 // 1024} MB 上限")
            out.write(chunk)

    with jobs_lock:
        jobs[job_id] = {
            "id": job_id,
            "filename": file.filename,
            "target": target,
            "media_path": str(dest),
            "media_type": file.content_type or "",
            "status": "queued",
            "stage": "排队中",
            "progress": 0.0,
            "language": None,
            "segments": [],
            "error": None,
            "burn": None,
            "dub": None,
            "theme": "",
            "terms": [],
            "created_at": time.time(),
        }
    executor.submit(_run_job, job_id, str(dest), language.strip() or None, api_key.strip() or None, target,
                    reflect not in ("0", "false", ""), terms, model or None, correct not in ("0", "false", ""))
    return {"id": job_id}


@app.get("/api/jobs/{job_id}")
def get_job(job_id: str):
    job = _get_job(job_id)
    job.pop("media_path", None)
    return job


class SegmentsUpdate(BaseModel):
    segments: list[dict]


@app.put("/api/jobs/{job_id}/segments")
def update_segments(job_id: str, body: SegmentsUpdate):
    """Save manual edits made in the web editor."""
    job = _get_job(job_id)
    if job["status"] != "done":
        raise HTTPException(409, "任务尚未完成")
    by_id = {s["id"]: s for s in job["segments"]}
    for edit in body.segments:
        seg = by_id.get(edit.get("id"))
        if seg is None:
            continue
        for key in ("text", "zh", "en"):
            if isinstance(edit.get(key), str):
                seg[key] = edit[key]
    _update(job_id, segments=list(by_id.values()))
    return {"ok": True}


def _segments(job_id: str) -> tuple[dict, list[Segment]]:
    job = _get_job(job_id)
    if not job["segments"]:
        raise HTTPException(409, "字幕尚未生成")
    return job, [Segment(**s) for s in job["segments"]]


@app.get("/api/jobs/{job_id}/subtitle.{fmt}")
def download_subtitle(job_id: str, fmt: str, mode: str = "bilingual", download: bool = False):
    if fmt not in SUBTITLE_TYPES:
        raise HTTPException(400, "格式只支持 srt / vtt / lrc")
    _check_mode(mode)
    job, segments = _segments(job_id)
    content = {"srt": to_srt, "vtt": to_vtt, "lrc": to_lrc}[fmt](segments, mode)
    headers = {}
    if download:
        headers["Content-Disposition"] = _attachment(job, mode, fmt)
    return PlainTextResponse(content, media_type=f"{SUBTITLE_TYPES[fmt]}; charset=utf-8", headers=headers)


@app.get("/api/jobs/{job_id}/export.mp3")
def download_mp3(job_id: str, mode: str = "bilingual"):
    """MP3 with the subtitles embedded as ID3 lyrics (videos are converted to MP3)."""
    _check_mode(mode)
    job, segments = _segments(job_id)
    title = Path(job["filename"] or job_id).stem
    try:
        with export_lock:
            path = export_mp3(job["media_path"], EXPORT_DIR, job_id, segments, mode, title)
    except RuntimeError as e:
        raise HTTPException(400, str(e))
    return FileResponse(path, media_type="audio/mpeg", headers={"Content-Disposition": _attachment(job, mode, "mp3")})


class BurnRequest(BaseModel):
    mode: str = "bilingual"


def _run_burn(job_id: str, media_path: str, segments: list[Segment], mode: str, dst: Path) -> None:
    def progress(p: float) -> None:
        _update(job_id, burn={"status": "running", "mode": mode, "progress": round(p, 3), "error": None})

    try:
        dst.parent.mkdir(parents=True, exist_ok=True)
        tmp = dst.with_suffix(".part.mp4")
        burn(media_path, str(tmp), segments, mode, on_progress=progress)
        tmp.replace(dst)
        _update(job_id, burn={"status": "done", "mode": mode, "progress": 1.0, "error": None})
    except Exception as e:
        _update(job_id, burn={"status": "error", "mode": mode, "progress": 0.0, "error": str(e)})


@app.post("/api/jobs/{job_id}/burn")
def start_burn(job_id: str, body: BurnRequest):
    """Burn the subtitles into the video (audio-only files get a black picture)."""
    _check_mode(body.mode)
    job, segments = _segments(job_id)
    with jobs_lock:
        if (jobs[job_id].get("burn") or {}).get("status") == "running":
            raise HTTPException(409, "正在烧录中，请等待完成")
        jobs[job_id]["burn"] = {"status": "running", "mode": body.mode, "progress": 0.0, "error": None}
    dst = EXPORT_DIR / f"{job_id}.burned.mp4"
    burn_executor.submit(_run_burn, job_id, job["media_path"], segments, body.mode, dst)
    return {"ok": True}


class DubRequest(BaseModel):
    lang: str = "zh"
    voice: str = ""
    bg_volume: float = 0.2
    burn_mode: str | None = None


def _run_dub(job_id: str, media_path: str, segments: list[Segment], req: DubRequest) -> None:
    state = {"status": "running", "lang": req.lang, "progress": 0.0, "error": None, "file": None}

    def progress(p: float) -> None:
        _update(job_id, dub={**state, "progress": round(p, 3)})

    try:
        out = dub(media_path, EXPORT_DIR / f"{job_id}_dubbed", segments, req.lang, req.voice,
                  req.bg_volume, req.burn_mode, on_progress=progress)
        _update(job_id, dub={**state, "status": "done", "progress": 1.0, "file": out.name})
    except Exception as e:
        _update(job_id, dub={**state, "status": "error", "error": str(e)})


@app.get("/api/voices")
def voices():
    return VOICES


@app.post("/api/jobs/{job_id}/dub")
def start_dub(job_id: str, req: DubRequest):
    """AI dubbing of the translated subtitles (edge-tts), mixed into the video."""
    if req.lang not in VOICES:
        raise HTTPException(400, "配音语言只支持 zh / en")
    if not req.voice:
        req.voice = VOICES[req.lang][0][0]
    if req.voice not in {v for v, _ in VOICES[req.lang]}:
        raise HTTPException(400, "不支持的配音音色")
    if req.burn_mode is not None:
        _check_mode(req.burn_mode)
    req.bg_volume = min(1.0, max(0.0, req.bg_volume))
    job, segments = _segments(job_id)
    if req.lang == "en" and job.get("target") == "zh":
        raise HTTPException(400, "这个任务只有中文字幕，不能配英文")
    with jobs_lock:
        if (jobs[job_id].get("dub") or {}).get("status") == "running":
            raise HTTPException(409, "正在配音中，请等待完成")
        jobs[job_id]["dub"] = {"status": "running", "lang": req.lang, "progress": 0.0, "error": None, "file": None}
    burn_executor.submit(_run_dub, job_id, job["media_path"], segments, req)
    return {"ok": True}


@app.get("/api/jobs/{job_id}/dubbed")
def download_dubbed(job_id: str):
    job = _get_job(job_id)
    state = job.get("dub") or {}
    if state.get("status") != "done" or not state.get("file") or not (EXPORT_DIR / state["file"]).exists():
        raise HTTPException(409, "配音还没有完成")
    path = EXPORT_DIR / state["file"]
    media_type = "video/mp4" if path.suffix == ".mp4" else "audio/mpeg"
    headers = {"Content-Disposition": _attachment(job, state["lang"], "dubbed" + path.suffix)}
    return FileResponse(path, media_type=media_type, headers=headers)


@app.get("/api/jobs/{job_id}/burned.mp4")
def download_burned(job_id: str):
    job = _get_job(job_id)
    state = job.get("burn") or {}
    path = EXPORT_DIR / f"{job_id}.burned.mp4"
    if state.get("status") != "done" or not path.exists():
        raise HTTPException(409, "视频还没有烧录完成")
    headers = {"Content-Disposition": _attachment(job, state["mode"], "subtitled.mp4")}
    return FileResponse(path, media_type="video/mp4", headers=headers)


def _check_mode(mode: str) -> None:
    if mode not in MODES:
        raise HTTPException(400, "mode 只支持 bilingual / zh / en / orig")


def _attachment(job: dict, mode: str, ext: str) -> str:
    stem = Path(job["filename"] or job["id"]).stem
    return f"attachment; filename*=UTF-8''{quote(f'{stem}.{mode}.{ext}')}"


@app.get("/api/jobs/{job_id}/media")
def get_media(job_id: str):
    with jobs_lock:
        job = jobs.get(job_id)
    if job is None or not Path(job["media_path"]).exists():
        raise HTTPException(404, "文件不存在")
    return FileResponse(job["media_path"], media_type=job["media_type"] or None)


# ---------------------------------------------------------------- images

image_executor = ThreadPoolExecutor(max_workers=1)
images: dict[str, dict] = {}


def _get_image(image_id: str) -> dict:
    with jobs_lock:
        item = images.get(image_id)
        if item is None:
            raise HTTPException(404, "图片任务不存在")
        return dict(item)


def _update_image(image_id: str, **fields) -> None:
    with jobs_lock:
        images[image_id].update(fields)


def _run_image(image_id: str, path: str, target: str, ocr_lang: str, api_key: str | None, terms_text: str) -> None:
    try:
        _update_image(image_id, status="ocr", stage="识别图片文字中（首次使用某种语言时需要下载模型）", progress=0.05)
        lines = image_translate.ocr(path, ocr_lang)
        if not lines:
            raise RuntimeError("图片里没有识别到文字")
        _update_image(image_id, lines=lines, status="translating", stage="DeepSeek 校正、翻译中", progress=0.3)
        info = image_translate.translate_lines(
            lines, target, api_key, ocr_lang, translator.parse_terms(terms_text),
            on_progress=lambda p: _update_image(image_id, progress=round(0.3 + p * 0.7, 3)),
        )
        _update_image(image_id, status="done", stage="完成", progress=1.0, lines=lines,
                      language=info["language"], theme=info["theme"], terms=info["terms"])
    except Exception as e:
        _update_image(image_id, status="error", stage="出错", error=str(e))


@app.post("/api/images")
async def create_image_job(
    file: UploadFile = File(...),
    target: str = Form("orig_zh"),
    ocr_lang: str = Form("auto"),
    api_key: str = Form(""),
    terms: str = Form(""),
):
    """Translate the text in an image (OCR + DeepSeek)."""
    if target not in image_translate.TARGETS:
        raise HTTPException(400, f"target 只支持 {' / '.join(image_translate.TARGETS)}")
    if ocr_lang not in image_translate.OCR_LANGS:
        raise HTTPException(400, f"ocr_lang 只支持 {' / '.join(image_translate.OCR_LANGS)}")
    if not _has_key(api_key.strip()):
        raise HTTPException(400, "请填写 DeepSeek API Key，或在服务端 .env 中配置 DEEPSEEK_API_KEY")
    ext = Path(file.filename or "").suffix.lower()
    if ext not in image_translate.IMAGE_EXT:
        raise HTTPException(400, f"不支持的图片格式 {ext or '(无扩展名)'}，支持: {', '.join(sorted(image_translate.IMAGE_EXT))}")
    data = await file.read()
    if len(data) > MAX_UPLOAD_BYTES:
        raise HTTPException(413, "图片太大")
    image_id = uuid.uuid4().hex[:12]
    dest = UPLOAD_DIR / f"img_{image_id}{ext}"
    dest.write_bytes(data)
    with jobs_lock:
        images[image_id] = {
            "id": image_id, "filename": file.filename, "path": str(dest), "target": target,
            "status": "queued", "stage": "排队中", "progress": 0.0, "lines": [], "error": None,
            "language": None, "theme": "", "terms": [],
        }
    image_executor.submit(_run_image, image_id, str(dest), target, ocr_lang, api_key.strip() or None, terms)
    return {"id": image_id}


@app.get("/api/images/{image_id}")
def get_image_job(image_id: str):
    item = _get_image(image_id)
    item.pop("path", None)
    return item


@app.get("/api/images/{image_id}/source")
def get_image_source(image_id: str):
    return FileResponse(_get_image(image_id)["path"])


def _image_mode(item: dict) -> str:
    if item["status"] != "done":
        raise HTTPException(409, "图片还没有翻译完成")
    return image_translate.TARGETS[item["target"]][1]


@app.get("/api/images/{image_id}/translated.png")
def get_translated_image(image_id: str, download: bool = False):
    item = _get_image(image_id)
    mode = _image_mode(item)
    out = EXPORT_DIR / f"img_{image_id}.translated.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    image_translate.render(item["path"], item["lines"], mode, str(out))
    headers = {}
    if download:
        stem = Path(item["filename"] or image_id).stem
        headers["Content-Disposition"] = f"attachment; filename*=UTF-8''{quote(stem + '.translated.png')}"
    return FileResponse(out, media_type="image/png", headers=headers)


@app.get("/api/images/{image_id}/text.txt")
def get_image_text(image_id: str):
    item = _get_image(image_id)
    text = image_translate.to_text(item["lines"], _image_mode(item))
    stem = Path(item["filename"] or image_id).stem
    headers = {"Content-Disposition": f"attachment; filename*=UTF-8''{quote(stem + '.translated.txt')}"}
    return PlainTextResponse(text, headers=headers)


@app.get("/api/config")
def config():
    return {
        "server_key_configured": bool(os.getenv("DEEPSEEK_API_KEY")),
        "whisper_model": os.getenv("WHISPER_MODEL", "small"),
        "deepseek_model": os.getenv("DEEPSEEK_MODEL", "deepseek-chat"),
        "allowed_ext": sorted(ALLOWED_EXT),
        "image_ext": sorted(image_translate.IMAGE_EXT),
        "whisper_models": list(transcriber.MODELS),
        "ocr_langs": {k: v[3] for k, v in image_translate.OCR_LANGS.items()},
    }


app.mount("/", StaticFiles(directory=STATIC_DIR, html=True), name="static")
