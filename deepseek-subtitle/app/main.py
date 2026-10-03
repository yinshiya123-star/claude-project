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
from .subtitles import Segment, to_srt, to_vtt  # noqa: E402

BASE_DIR = Path(__file__).resolve().parent.parent
STATIC_DIR = BASE_DIR / "static"
UPLOAD_DIR = Path(os.getenv("UPLOAD_DIR", BASE_DIR / "data" / "uploads"))
UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
MAX_UPLOAD_BYTES = int(os.getenv("MAX_UPLOAD_MB", "500")) * 1024 * 1024
ALLOWED_EXT = {".mp3", ".mp4", ".m4a", ".wav", ".flac", ".ogg", ".aac", ".webm", ".mov", ".mkv"}

app = FastAPI(title="DeepSeek 中英字幕生成器")

# Whisper is CPU/GPU heavy: process one job at a time.
executor = ThreadPoolExecutor(max_workers=1)
jobs: dict[str, dict] = {}
jobs_lock = threading.Lock()


def _update(job_id: str, **fields) -> None:
    with jobs_lock:
        jobs[job_id].update(fields)


def _get_job(job_id: str) -> dict:
    with jobs_lock:
        job = jobs.get(job_id)
        if job is None:
            raise HTTPException(404, "任务不存在")
        return dict(job)


def _run_job(job_id: str, path: str, language: str | None, api_key: str | None) -> None:
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

        _update(job_id, status="translating", stage="DeepSeek 翻译中", progress=0.6)
        translator.translate(
            segments,
            api_key=api_key,
            on_progress=lambda p: _update(job_id, progress=round(0.6 + p * 0.4, 3)),
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
):
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

    if not (api_key.strip() or os.getenv("DEEPSEEK_API_KEY")):
        dest.unlink(missing_ok=True)
        raise HTTPException(400, "请填写 DeepSeek API Key，或在服务端 .env 中配置 DEEPSEEK_API_KEY")

    with jobs_lock:
        jobs[job_id] = {
            "id": job_id,
            "filename": file.filename,
            "media_path": str(dest),
            "media_type": file.content_type or "",
            "status": "queued",
            "stage": "排队中",
            "progress": 0.0,
            "language": None,
            "segments": [],
            "error": None,
            "created_at": time.time(),
        }
    executor.submit(_run_job, job_id, str(dest), language.strip() or None, api_key.strip() or None)
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
        for key in ("zh", "en"):
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
    if fmt not in ("srt", "vtt"):
        raise HTTPException(400, "格式只支持 srt / vtt")
    if mode not in ("bilingual", "zh", "en"):
        raise HTTPException(400, "mode 只支持 bilingual / zh / en")
    job, segments = _segments(job_id)
    content = to_srt(segments, mode) if fmt == "srt" else to_vtt(segments, mode)
    headers = {}
    if download:
        stem = Path(job["filename"] or job_id).stem
        filename = f"{stem}.{mode}.{fmt}"
        headers["Content-Disposition"] = f"attachment; filename*=UTF-8''{quote(filename)}"
    media_type = "text/vtt" if fmt == "vtt" else "application/x-subrip"
    return PlainTextResponse(content, media_type=f"{media_type}; charset=utf-8", headers=headers)


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
