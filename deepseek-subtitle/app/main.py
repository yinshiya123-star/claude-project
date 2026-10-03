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
from .export import export_mp3  # noqa: E402
from .subtitles import Segment, to_lrc, to_srt, to_vtt  # noqa: E402

BASE_DIR = Path(__file__).resolve().parent.parent
STATIC_DIR = BASE_DIR / "static"
UPLOAD_DIR = Path(os.getenv("UPLOAD_DIR", BASE_DIR / "data" / "uploads"))
UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
EXPORT_DIR = UPLOAD_DIR.parent / "exports"
MAX_UPLOAD_BYTES = int(os.getenv("MAX_UPLOAD_MB", "500")) * 1024 * 1024
ALLOWED_EXT = {".mp3", ".mp4", ".m4a", ".wav", ".flac", ".ogg", ".aac", ".webm", ".mov", ".mkv"}

app = FastAPI(title="DeepSeek 中英字幕生成器")

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


def _run_job(job_id: str, path: str, language: str | None, api_key: str | None, target: str) -> None:
    try:
        _update(job_id, status="transcribing", stage="语音识别中（首次运行需下载模型）", progress=0.0)
        segments, lang = transcriber.transcribe(
            path,
            language=language,
            on_progress=lambda p: _update(job_id, progress=round(p * 0.6, 3), stage="语音识别中"),
        )
        _update(job_id, language=lang, segments=[s.to_dict() for s in segments])
        if not segments:
            raise RuntimeError("没有识别到任何语音")

        if target == "zh" and not _has_key(api_key):
            # Chinese-only without a key: use the recognised text as is.
            for seg in segments:
                seg.zh, seg.en = seg.text, ""
        else:
            stage = "DeepSeek 校对中" if target == "zh" else "DeepSeek 翻译中"
            _update(job_id, status="translating", stage=stage, progress=0.6)
            translator.translate(
                segments,
                api_key=api_key,
                on_progress=lambda p: _update(job_id, progress=round(0.6 + p * 0.4, 3)),
                target=target,
            )
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
):
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
            "created_at": time.time(),
        }
    executor.submit(_run_job, job_id, str(dest), language.strip() or None, api_key.strip() or None, target)
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


@app.get("/api/config")
def config():
    return {
        "server_key_configured": bool(os.getenv("DEEPSEEK_API_KEY")),
        "whisper_model": os.getenv("WHISPER_MODEL", "small"),
        "deepseek_model": os.getenv("DEEPSEEK_MODEL", "deepseek-chat"),
        "allowed_ext": sorted(ALLOWED_EXT),
    }


app.mount("/", StaticFiles(directory=STATIC_DIR, html=True), name="static")
