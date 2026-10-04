"""Subtitle translation and proofreading via the DeepSeek API.

The pipeline follows VideoLingo (https://github.com/Huanshere/VideoLingo,
Apache-2.0); the prompts below are adapted from its core/prompts.py:

1. Summarise the whole transcript and extract terms (names, jargon) so they
   are translated consistently.
2. Translate in chunks of about 600 characters, each with the previous and
   next lines, the summary and the terms that occur in it as context.
3. Two steps per chunk: a faithful translation, then a reflection on it and a
   free, natural rewrite ("translate - reflect - adapt"). If the second step
   fails, the faithful translation is kept.

Changes from VideoLingo: chunks are translated into Chinese and/or English to
fill the zh / en fields, a proofreading pass handles Chinese sources, terms
carry both Chinese and English, and requests use DeepSeek's JSON mode.
"""

from __future__ import annotations

import json
import os
import threading
from concurrent.futures import ThreadPoolExecutor
from typing import Callable

from openai import OpenAI

from .subtitles import Segment

CHUNK_CHARS = 600  # VideoLingo: split_chunks_by_chars(chunk_size=600, max_i=10)
CHUNK_LINES = 10
CONTEXT_BEFORE = 3
CONTEXT_AFTER = 2
SUMMARY_CHARS = 8000
MAX_TERMS = 15
MAX_RETRIES = 2
MAX_WORKERS = 4

LANG_NAMES = {
    "zh": "Chinese", "en": "English", "ja": "Japanese", "ko": "Korean", "fr": "French",
    "de": "German", "es": "Spanish", "ru": "Russian", "pt": "Portuguese", "it": "Italian",
    "ar": "Arabic", "th": "Thai", "vi": "Vietnamese", "id": "Indonesian", "ms": "Malay",
    "hi": "Hindi", "tr": "Turkish", "nl": "Dutch", "pl": "Polish", "uk": "Ukrainian", "yue": "Cantonese",
}
TARGETS = {"zh": "Simplified Chinese", "en": "English"}


class TranslationError(RuntimeError):
    pass


def make_client(api_key: str | None = None) -> OpenAI:
    key = api_key or os.getenv("DEEPSEEK_API_KEY")
    if not key:
        raise TranslationError("未配置 DeepSeek API Key（设置环境变量 DEEPSEEK_API_KEY 或在页面中填写）")
    return OpenAI(api_key=key, base_url=os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com"))


def parse_terms(text: str) -> list[dict]:
    """User glossary, one 'source=translation' per line."""
    terms = []
    for line in (text or "").splitlines():
        for sep in ("=", "＝", "\t"):
            if sep in line:
                src, tgt = (part.strip() for part in line.split(sep, 1))
                if src and tgt:
                    terms.append({"src": src, "zh": tgt, "en": tgt, "note": "用户指定"})
                break
    return terms


# ---------------------------------------------------------------- prompts

def summary_prompt(text: str, src_lang: str, custom_terms: list[dict]) -> str:
    existing = ""
    if custom_terms:
        existing = "\n### Existing Terms\nPlease exclude these terms in your extraction:\n" + "\n".join(
            f"- {t['src']}" for t in custom_terms
        )
    return f"""
## Role
You are a video translation expert and terminology consultant, specializing in {src_lang} comprehension and Chinese / English expression.

## Task
For the provided {src_lang} video text:
1. Summarize the main topic in two sentences, in Simplified Chinese
2. Extract professional terms and names with their Simplified Chinese and English translations (excluding existing terms)
3. Provide a brief Chinese explanation for each term
{existing}

Steps:
1. Topic Summary: first sentence for the main topic, second for the key point
2. Term Extraction: mark professional terms and names, give the translations (keep the original if it should not be translated), extract less than {MAX_TERMS} terms

## INPUT
<text>
{text}
</text>

## Output in only JSON format and no other text
{{
  "theme": "Two-sentence video summary",
  "terms": [
    {{"src": "{src_lang} term", "zh": "Chinese translation", "en": "English translation", "note": "Brief explanation"}}
  ]
}}
""".strip()


def _shared_context(before: list[str], after: list[str], theme: str, terms: list[dict]) -> str:
    notes = "\n".join(
        f"- {t['src']}: Chinese \"{t.get('zh', '')}\", English \"{t.get('en', '')}\" ({t.get('note', '')})"
        for t in terms
    )
    return f"""### Context Information
<previous_content>
{chr(10).join(before) or "None"}
</previous_content>

<subsequent_content>
{chr(10).join(after) or "None"}
</subsequent_content>

### Content Summary
{theme or "None"}

### Points to Note
{notes or "None"}"""


def _numbered(lines: list[str], fields: dict[str, str]) -> str:
    return json.dumps(
        {str(i): {"origin": line, **fields} for i, line in enumerate(lines, 1)},
        indent=2, ensure_ascii=False,
    )


def faithfulness_prompt(lines: list[str], src_lang: str, target: str, shared: str) -> str:
    return f"""
## Role
You are a professional Netflix subtitle translator, fluent in both {src_lang} and {target}, as well as their respective cultures.
Your expertise lies in accurately understanding the semantics and structure of the original {src_lang} text and faithfully translating it into {target} while preserving the original meaning.

## Task
We have a segment of original subtitles that need to be directly translated into {target}. These subtitles come from a specific context and may contain specific themes and terminology. Some lines may be in another language than {src_lang}; translate them too. A line already in {target} is kept, with obvious recognition errors fixed.

1. Translate the original subtitles into {target} line by line
2. Ensure the translation is faithful to the original, accurately conveying the original meaning
3. Consider the context and professional terminology

{shared}

<translation_principles>
1. Faithful to the original: Accurately convey the content and meaning of the original text, without arbitrarily changing, adding, or omitting content.
2. Accurate terminology: Use professional terms correctly and maintain consistency in terminology.
3. Understand the context: Fully comprehend and reflect the background and contextual relationships of the text.
</translation_principles>

## INPUT
<subtitles>
{chr(10).join(lines)}
</subtitles>

## Output in only JSON format and no other text
{_numbered(lines, {"direct": f"direct {target} translation"})}
""".strip()


def expressiveness_prompt(lines: list[str], direct: list[str], src_lang: str, target: str, shared: str) -> str:
    template = json.dumps(
        {
            str(i): {"origin": o, "direct": d, "reflect": "your reflection on direct translation", "free": "your free translation"}
            for i, (o, d) in enumerate(zip(lines, direct), 1)
        },
        indent=2, ensure_ascii=False,
    )
    return f"""
## Role
You are a professional Netflix subtitle translator and language consultant.
Your expertise lies not only in accurately understanding the original {src_lang} but also in optimizing the {target} translation to better suit the target language's expression habits and cultural background.

## Task
We already have a direct translation version of the original subtitles.
Your task is to reflect on and improve these direct translations to create more natural and fluent {target} subtitles.

1. Analyze the direct translation results line by line, pointing out existing issues
2. Provide detailed modification suggestions
3. Perform free translation based on your analysis
4. Do not add comments or explanations in the translation, as the subtitles are for the audience to read
5. Do not leave empty lines in the free translation, as the subtitles are for the audience to read

{shared}

<Translation Analysis Steps>
Please use a two-step thinking process to handle the text line by line:

1. Direct Translation Reflection:
   - Evaluate language fluency
   - Check if the language style is consistent with the original text
   - Check the conciseness of the subtitles, point out where the translation is too wordy

2. {target} Free Translation:
   - Aim for contextual smoothness and naturalness, conforming to {target} expression habits
   - Ensure it's easy for {target} audience to understand and accept
   - Adapt the language style to match the theme (e.g., use casual language for tutorials, professional terminology for technical content, formal language for documentaries)
</Translation Analysis Steps>

## INPUT
<subtitles>
{chr(10).join(lines)}
</subtitles>

## Output in only JSON format and no other text
{template}
""".strip()


def proofread_prompt(lines: list[str], shared: str) -> str:
    return f"""
## Role
You are a professional Chinese subtitle proofreader.

## Task
The subtitles below come from speech recognition of a mostly Chinese video. Proofread them line by line into the "zh" field:
1. Fix homophones, typos and obvious recognition errors using the context and the terms
2. Use Simplified Chinese and add proper punctuation
3. A line in another language is translated into Simplified Chinese
4. Do not rewrite, expand or shorten the meaning; keep the spoken style; never merge or split lines

{shared}

## INPUT
<subtitles>
{chr(10).join(lines)}
</subtitles>

## Output in only JSON format and no other text
{_numbered(lines, {"zh": "proofread Simplified Chinese line"})}
""".strip()


# ---------------------------------------------------------------- requests

class _Client:
    def __init__(self, api_key: str | None):
        self.client = make_client(api_key)
        self.model = os.getenv("DEEPSEEK_MODEL", "deepseek-chat")

    def ask_json(self, prompt: str, valid: Callable[[dict], bool], label: str, temperature: float = 1.3) -> dict:
        last_error: Exception | None = None
        for _ in range(MAX_RETRIES + 1):
            try:
                resp = self.client.chat.completions.create(
                    model=self.model,
                    # JSON mode needs the word "json" in the messages.
                    messages=[
                        {"role": "system", "content": "Reply with a valid json object only."},
                        {"role": "user", "content": prompt},
                    ],
                    response_format={"type": "json_object"},
                    temperature=temperature,  # 1.3: DeepSeek's recommended setting for translation
                )
                data = json.loads(resp.choices[0].message.content or "{}")
                if isinstance(data, dict) and valid(data):
                    return data
                last_error = TranslationError("返回的 JSON 不完整")
            except Exception as e:  # network / JSON / API errors -> retry
                last_error = e
        raise TranslationError(f"DeepSeek {label}失败: {last_error}")


def _lines_valid(n: int, field: str) -> Callable[[dict], bool]:
    def valid(data: dict) -> bool:
        return all(isinstance(data.get(str(i)), dict) and str(data[str(i)].get(field, "")).strip() for i in range(1, n + 1))
    return valid


def _field(data: dict, n: int, field: str) -> list[str]:
    return [str(data[str(i)][field]).replace("\n", " ").strip() for i in range(1, n + 1)]


def chunk_lines(lines: list[str]) -> list[list[int]]:
    """Indexes of lines grouped into chunks of <= CHUNK_CHARS chars and <= CHUNK_LINES lines."""
    chunks, current, size = [], [], 0
    for i, line in enumerate(lines):
        if current and (size + len(line) + 1 > CHUNK_CHARS or len(current) == CHUNK_LINES):
            chunks.append(current)
            current, size = [], 0
        current.append(i)
        size += len(line) + 1
    if current:
        chunks.append(current)
    return chunks


def _matched_terms(terms: list[dict], text: str) -> list[dict]:
    lowered = text.lower()
    return [t for t in terms if t.get("src") and str(t["src"]).lower() in lowered]


def summarize(client: _Client, lines: list[str], src_lang: str, custom_terms: list[dict]) -> tuple[str, list[dict]]:
    text = "\n".join(lines)[:SUMMARY_CHARS]
    data = client.ask_json(
        summary_prompt(text, src_lang, custom_terms),
        lambda d: isinstance(d.get("theme"), str) and isinstance(d.get("terms", []), list),
        "总结",
    )
    given = {t["src"].lower() for t in custom_terms}  # the user's glossary wins over extracted terms
    terms = [
        {"src": str(t["src"]), "zh": str(t.get("zh", "")), "en": str(t.get("en", "")), "note": str(t.get("note", ""))}
        for t in data.get("terms", [])
        if isinstance(t, dict) and t.get("src") and str(t["src"]).lower() not in given
    ][:MAX_TERMS]
    return data["theme"].strip(), custom_terms + terms


def _run_chunk(client: _Client, task: str, lines: list[str], idx: list[int], src_lang: str,
               theme: str, terms: list[dict], reflect: bool) -> list[str]:
    part = [lines[i] for i in idx]
    shared = _shared_context(
        lines[max(0, idx[0] - CONTEXT_BEFORE) : idx[0]],
        lines[idx[-1] + 1 : idx[-1] + 1 + CONTEXT_AFTER],
        theme,
        _matched_terms(terms, "\n".join(part)),
    )
    n = len(part)
    if task == "proofread":
        data = client.ask_json(proofread_prompt(part, shared), _lines_valid(n, "zh"), "校对", temperature=0.7)
        return _field(data, n, "zh")
    target = TARGETS[task]
    data = client.ask_json(faithfulness_prompt(part, src_lang, target, shared), _lines_valid(n, "direct"), "翻译")
    direct = _field(data, n, "direct")
    if not reflect:
        return direct
    try:
        data = client.ask_json(expressiveness_prompt(part, direct, src_lang, target, shared), _lines_valid(n, "free"), "润色")
        return _field(data, n, "free")
    except TranslationError:
        return direct  # the polish step is optional, as in VideoLingo


def translate(
    segments: list[Segment],
    api_key: str | None = None,
    on_progress: Callable[[float], None] | None = None,
    target: str = "bilingual",
    source_lang: str | None = None,
    reflect: bool = True,
    custom_terms: list[dict] | None = None,
) -> dict:
    """Fill in `zh` and `en` for every segment, in place; returns {"theme", "terms"}.

    target="bilingual": Chinese + English. A Chinese source is proofread into
    zh and translated into en; an English source keeps its text as en.
    target="zh": proofread into zh only, en stays empty.
    """
    if not segments:
        return {"theme": "", "terms": []}
    client = _Client(api_key)
    lines = [s.text for s in segments]
    src_lang = LANG_NAMES.get(source_lang or "", source_lang or "the original language")

    if target == "zh":
        tasks = {"zh": "proofread"}
    elif source_lang == "zh":
        tasks = {"zh": "proofread", "en": "en"}
    elif source_lang == "en":
        tasks = {"zh": "zh"}
    else:
        tasks = {"zh": "zh", "en": "en"}

    theme, terms = summarize(client, lines, src_lang, custom_terms or [])
    chunks = chunk_lines(lines)
    jobs = [(field, task, idx) for field, task in tasks.items() for idx in chunks]
    done, lock = [0], threading.Lock()
    if on_progress:
        on_progress(0.1)

    def work(job):
        field, task, idx = job
        result = _run_chunk(client, task, lines, idx, src_lang, theme, terms, reflect)
        with lock:
            done[0] += 1
            if on_progress:
                on_progress(0.1 + 0.9 * done[0] / len(jobs))
        return field, idx, result

    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as pool:
        results = list(pool.map(work, jobs))

    for seg in segments:
        seg.zh = ""
        seg.en = seg.text if (target == "bilingual" and source_lang == "en") else ""
    for field, idx, result in results:
        for i, text in zip(idx, result):
            setattr(segments[i], field, text or segments[i].text)
    return {"theme": theme, "terms": terms}
