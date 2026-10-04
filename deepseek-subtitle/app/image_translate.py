"""Translate the text in images: OCR with RapidOCR, translation with DeepSeek.

RapidOCR runs PaddleOCR's models through onnxruntime (already installed for
faster-whisper). Its bundled model reads Chinese, English and Japanese; other
scripts download a recognition model on first use.

The recognised lines go through the same pipeline as subtitles (correction,
terms, two-step translation), then a translated image is drawn:
- single language: the original text is covered with the surrounding
  background colour and the translation is written in its place, in the
  original text colour;
- bilingual: the original stays and each line gets its translation in a label
  just below it.
"""

from __future__ import annotations

import logging
import re
import threading
from functools import lru_cache

from .subtitles import Segment

IMAGE_EXT = {".png", ".jpg", ".jpeg", ".webp", ".bmp"}
MIN_SCORE = 0.5

# web option -> (RapidOCR lang_type, ocr_version, model_type, label)
OCR_LANGS = {
    "auto": ("ch", "PP-OCRv6", "small", "自动（中文 / 英文 / 日文）"),
    "ko": ("korean", "PP-OCRv5", "mobile", "韩文"),
    "latin": ("latin", "PP-OCRv5", "mobile", "法 / 德 / 西 / 意 / 葡等拉丁字母语言"),
    "ru": ("eslav", "PP-OCRv5", "mobile", "俄文 / 乌克兰文等"),
    "th": ("th", "PP-OCRv5", "mobile", "泰文"),
    "el": ("el", "PP-OCRv5", "mobile", "希腊文"),
    "ar": ("arabic", "PP-OCRv4", "mobile", "阿拉伯文"),
    "hi": ("devanagari", "PP-OCRv4", "mobile", "印地文"),
}
# output option -> (fields to fill, lines drawn on the image)
TARGETS = {
    "zh": (("zh",), "zh"),
    "en": (("en",), "en"),
    "orig_zh": (("zh",), "bilingual"),
    "zh_en": (("zh", "en"), "bilingual"),
}

_lock = threading.Lock()


@lru_cache(maxsize=4)
def _engine(lang: str):
    from rapidocr import LangRec, ModelType, OCRVersion, RapidOCR

    logging.getLogger("RapidOCR").setLevel(logging.WARNING)
    lang_type, version, model_type, _ = OCR_LANGS[lang]
    if lang == "auto":
        return RapidOCR()
    return RapidOCR(params={
        "Rec.lang_type": LangRec(lang_type),
        "Rec.ocr_version": OCRVersion(version),
        "Rec.model_type": ModelType(model_type),
    })


def ocr(image, lang: str = "auto") -> list[dict]:
    """Text lines as {"box": [[x, y] * 4], "text", "score"}, in reading order.
    `image` is a file path or an RGB numpy array (e.g. a video frame)."""
    if not isinstance(image, (str, bytes)) and hasattr(image, "shape"):
        image = image[..., ::-1].copy()  # RapidOCR takes arrays as BGR
    with _lock:  # RapidOCR engines aren't thread-safe
        result = _engine(lang)(image)
    lines = []
    for box, text, score in zip(result.boxes if result.boxes is not None else [], result.txts or [], result.scores or []):
        if text.strip() and float(score) >= MIN_SCORE:
            lines.append({"box": [[float(x), float(y)] for x, y in box], "text": text.strip(), "score": round(float(score), 3)})
    # top to bottom, then left to right within a row
    lines.sort(key=lambda l: (round(min(p[1] for p in l["box"]) / 20), min(p[0] for p in l["box"])))
    return lines


def guess_language(texts: list[str]) -> str | None:
    joined = "".join(texts)
    if not joined:
        return None
    if re.search(r"[぀-ヿ]", joined):
        return "ja"
    if re.search(r"[가-힯]", joined):
        return "ko"
    cjk = len(re.findall(r"[一-鿿]", joined))
    if cjk > len(joined) / 3:
        return "zh"
    if joined.isascii():
        return "en"
    return None


def translate_lines(lines: list[dict], target: str, api_key: str | None, ocr_lang: str = "auto",
                    custom_terms: list[dict] | None = None, on_progress=None) -> dict:
    """Fill zh / en for every OCR line, in place. Returns {"theme", "terms", "language"}."""
    from . import translator

    segments = [Segment(id=i + 1, start=float(i), end=float(i) + 1, text=l["text"]) for i, l in enumerate(lines)]
    language = guess_language([l["text"] for l in lines]) or {"ko": "ko", "ru": "ru", "th": "th",
                                                               "el": "el", "ar": "ar", "hi": "hi"}.get(ocr_lang)
    fields, _ = TARGETS[target]
    info = translator.translate(segments, api_key=api_key, on_progress=on_progress, source_lang=language,
                                custom_terms=custom_terms, fields=fields)
    for line, seg in zip(lines, segments):
        line.update(text=seg.text, zh=seg.zh, en=seg.en)
    return {**info, "language": language}


# ---------------------------------------------------------------- drawing

def _rect(box) -> tuple[int, int, int, int]:
    xs, ys = [p[0] for p in box], [p[1] for p in box]
    return int(min(xs)), int(min(ys)), int(max(xs)) + 1, int(max(ys)) + 1


def _colors(arr, rect):
    """(background, text) colours of a text box: the background is the median of
    a thin frame around the box, the text the pixels least like it."""
    import numpy as np

    h, w = arr.shape[:2]
    x0, y0, x1, y1 = rect
    pad = 3
    frame = np.concatenate([
        arr[max(0, y0 - pad):y0, max(0, x0):x1].reshape(-1, 3),
        arr[y1:min(h, y1 + pad), max(0, x0):x1].reshape(-1, 3),
        arr[max(0, y0):y1, max(0, x0 - pad):x0].reshape(-1, 3),
        arr[max(0, y0):y1, x1:min(w, x1 + pad)].reshape(-1, 3),
    ])
    inside = arr[max(0, y0):y1, max(0, x0):x1].reshape(-1, 3)
    bg = np.median(frame if len(frame) else inside, axis=0)
    if not len(inside):
        return tuple(int(c) for c in bg), (0, 0, 0)
    dist = np.abs(inside.astype(int) - bg).sum(axis=1)
    far = inside[dist >= np.percentile(dist, 90)]
    fg = np.median(far, axis=0) if len(far) else (255 - bg)
    if np.abs(fg - bg).sum() < 60:  # too little contrast: plain black or white
        fg = np.array([0, 0, 0]) if bg.mean() > 128 else np.array([255, 255, 255])
    return tuple(int(c) for c in bg), tuple(int(c) for c in fg)


def _fit(draw, text, font_path, width, height):
    """Largest font (and line split) that fits `text` into width x height."""
    from PIL import ImageFont

    from .burn import _wrap

    size = max(10, int(height * 0.85))
    while True:
        font = ImageFont.truetype(font_path, size)
        rows = _wrap(text, font, width)
        line_h = font.getbbox("国Ag")[3]
        if (len(rows) * line_h <= height * 1.05 and all(font.getlength(r) <= width for r in rows)) or size <= 10:
            return font, rows, line_h
        size -= 1


def make_patch(arr, line: dict, mode: str, font_path: str):
    """Overlay for one OCR line as (x, y, RGBA array), or None.

    mode "zh" / "en": the text box is covered with its background colour and
    the translation is written in, in the original text colour.
    mode "bilingual": a dark label with the translation just below the box.
    `arr` is the RGB picture the colours are taken from.
    """
    import numpy as np
    from PIL import Image, ImageDraw, ImageFont

    from .burn import _wrap

    height_px, width_px = arr.shape[:2]
    x0, y0, x1, y1 = _rect(line["box"])
    x0, y0 = max(0, x0), max(0, y0)
    x1, y1 = min(width_px, x1), min(height_px, y1)
    w, h = max(1, x1 - x0), max(1, y1 - y0)

    if mode in ("zh", "en"):
        text = line.get(mode) or line["text"]
        bg, fg = _colors(arr, (x0, y0, x1, y1))
        px, py = max(0, x0 - 1), max(0, y0 - 1)
        img = Image.new("RGBA", (min(width_px, x1 + 1) - px, min(height_px, y1 + 1) - py), bg + (255,))
        draw = ImageDraw.Draw(img)
        font, rows, line_h = _fit(draw, text, font_path, w, h)
        top = (y0 - py) + (h - line_h * len(rows)) / 2
        for i, row in enumerate(rows):  # left-aligned like most text in images
            draw.text((x0 - px, top + i * line_h), row, font=font, fill=fg + (255,))
        return px, py, np.asarray(img)

    text = " / ".join(dict.fromkeys(t for t in (line.get("zh"), line.get("en")) if t and t != line["text"]))
    if not text:
        return None
    font = ImageFont.truetype(font_path, max(12, int(h * 0.55)))
    rows = _wrap(text, font, max(w, width_px * 0.5))
    line_h = font.getbbox("国Ag")[3]
    label_w = int(min(width_px, max(font.getlength(r) for r in rows) + 12))
    label_h = int(line_h * len(rows) + 8)
    ly = y1 + 2 if y1 + 2 + label_h <= height_px else max(0, y0 - label_h - 2)
    lx = min(x0, max(0, width_px - label_w))
    img = Image.new("RGBA", (label_w, label_h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle([0, 0, label_w - 1, label_h - 1], radius=6, fill=(20, 20, 30, 215))
    for i, row in enumerate(rows):
        draw.text((6, 4 + i * line_h), row, font=font, fill=(255, 255, 255, 255))
    return lx, ly, np.asarray(img)


def composite(arr, x: int, y: int, patch) -> None:
    """Alpha-blend an RGBA patch onto an RGB uint8 array in place (clipped to the picture)."""
    h, w = arr.shape[:2]
    ph, pw = patch.shape[:2]
    x1, y1 = min(w, x + pw), min(h, y + ph)
    if x1 <= x or y1 <= y:
        return
    part = patch[: y1 - y, : x1 - x].astype("float32")
    alpha = part[..., 3:] / 255.0
    region = arr[y:y1, x:x1].astype("float32")
    arr[y:y1, x:x1] = (region * (1 - alpha) + part[..., :3] * alpha).astype("uint8")


def render(image_path: str, lines: list[dict], mode: str, out_path: str) -> None:
    """Draw the translated image. mode: "zh" / "en" replace the text, "bilingual" adds labels."""
    import numpy as np
    from PIL import Image

    from .burn import find_font

    font_path = find_font()
    source = np.asarray(Image.open(image_path).convert("RGB"))
    out = source.copy()
    for line in lines:
        patch = make_patch(source, line, mode, font_path)  # colours from the untouched picture
        if patch is not None:
            composite(out, *patch)
    Image.fromarray(out).save(out_path)


def to_text(lines: list[dict], mode: str) -> str:
    out = []
    for line in lines:
        if mode in ("zh", "en"):
            out.append(line.get(mode) or line["text"])
        else:
            texts = [t for t in (line["text"], line.get("zh"), line.get("en")) if t]
            out.append("\n".join(dict.fromkeys(texts)))  # drop a translation equal to the original
    return ("\n\n" if mode == "bilingual" else "\n").join(out) + "\n"
