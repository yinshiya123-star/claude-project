"""Bilingual (Chinese / English) subtitle translation via the DeepSeek API."""

from __future__ import annotations

import json
import os
from typing import Callable

from openai import OpenAI

from .subtitles import Segment

BATCH_SIZE = 30
CONTEXT_LINES = 3
MAX_RETRIES = 2

SYSTEM_PROMPT = """你是专业的影视字幕翻译。用户会给出一组按顺序排列、带编号的语音识别字幕行（可能是中文、英文或其他语言）。
请为每一行同时给出简体中文和英文版本：
- 原文是中文：zh 字段为修正明显识别错误后的原文，en 字段为地道的英文翻译；
- 原文是英文：en 字段为修正明显识别错误后的原文，zh 字段为自然流畅的简体中文翻译；
- 原文是其他语言：分别翻译成简体中文和英文。
要求：结合上下文翻译，保持口语化、简洁，适合做字幕；不要合并或拆分行，id 必须与输入一一对应；人名、术语前后保持一致。
只输出 JSON，格式：{"items": [{"id": 1, "zh": "...", "en": "..."}]}"""

ZH_PROMPT = """你是专业的中文字幕校对。用户会给出一组按顺序排列、带编号的中文语音识别字幕行。
请逐行校对，放进 zh 字段：
- 结合上下文修正同音字、错别字和明显的识别错误；
- 统一使用简体中文，补上恰当的标点；
- 不要改写、扩写或删减原意，保持口语风格；不要合并或拆分行，id 必须与输入一一对应。
只输出 JSON，格式：{"items": [{"id": 1, "zh": "..."}]}"""

# target -> (system prompt, instruction for the lines, temperature, failure label)
TASKS = {
    "bilingual": (SYSTEM_PROMPT, "请翻译 lines 中的每一行", 1.3, "翻译"),  # 1.3: DeepSeek's setting for translation
    "zh": (ZH_PROMPT, "请校对 lines 中的每一行", 0.7, "校对"),
}


class TranslationError(RuntimeError):
    pass


def make_client(api_key: str | None = None) -> OpenAI:
    key = api_key or os.getenv("DEEPSEEK_API_KEY")
    if not key:
        raise TranslationError("未配置 DeepSeek API Key（设置环境变量 DEEPSEEK_API_KEY 或在页面中填写）")
    return OpenAI(api_key=key, base_url=os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com"))


def _build_user_message(batch: list[Segment], context: list[Segment], instruction: str) -> str:
    payload = {
        "context_before": [s.text for s in context],
        "lines": [{"id": s.id, "text": s.text} for s in batch],
    }
    return (
        f"context_before 仅供理解上下文，不需要处理。{instruction}：\n"
        + json.dumps(payload, ensure_ascii=False)
    )


def _translate_batch(
    client: OpenAI, model: str, batch: list[Segment], context: list[Segment], target: str
) -> dict[int, dict]:
    prompt, instruction, temperature, label = TASKS[target]
    expected = {s.id for s in batch}
    last_error: Exception | None = None
    for _ in range(MAX_RETRIES + 1):
        try:
            resp = client.chat.completions.create(
                model=model,
                messages=[
                    {"role": "system", "content": prompt},
                    {"role": "user", "content": _build_user_message(batch, context, instruction)},
                ],
                response_format={"type": "json_object"},
                temperature=temperature,
            )
            data = json.loads(resp.choices[0].message.content or "{}")
            result = {
                int(item["id"]): item
                for item in data.get("items", [])
                if isinstance(item, dict) and "id" in item
            }
            if expected.issubset(result):
                return result
            last_error = TranslationError(f"返回结果缺少行: {sorted(expected - set(result))}")
        except Exception as e:  # network / JSON / API errors -> retry
            last_error = e
    raise TranslationError(f"DeepSeek {label}失败: {last_error}")


def translate(
    segments: list[Segment],
    api_key: str | None = None,
    on_progress: Callable[[float], None] | None = None,
    target: str = "bilingual",
) -> list[Segment]:
    """Fill in `zh` and `en` for every segment, in place.

    target="bilingual" translates to Chinese + English; target="zh" only
    proofreads Chinese text into `zh` and leaves `en` empty.
    """
    if not segments:
        return segments
    client = make_client(api_key)
    model = os.getenv("DEEPSEEK_MODEL", "deepseek-chat")
    for start in range(0, len(segments), BATCH_SIZE):
        batch = segments[start : start + BATCH_SIZE]
        context = segments[max(0, start - CONTEXT_LINES) : start]
        result = _translate_batch(client, model, batch, context, target)
        for seg in batch:
            item = result[seg.id]
            seg.zh = str(item.get("zh") or seg.text).strip()
            seg.en = str(item.get("en") or seg.text).strip() if target == "bilingual" else ""
        if on_progress:
            on_progress(min(1.0, (start + len(batch)) / len(segments)))
    return segments
