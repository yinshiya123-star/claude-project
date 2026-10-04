# 第三方声明 / Third-party notices

## VideoLingo

- 项目：https://github.com/Huanshere/VideoLingo
- 许可证：Apache License 2.0（全文见 [`licenses/VideoLingo-LICENSE.txt`](licenses/VideoLingo-LICENSE.txt)）

本项目借鉴了 VideoLingo 的字幕处理流程，部分内容改编自它的源代码：

| 本项目文件 | 借鉴 / 改编自 VideoLingo | 主要改动 |
| --- | --- | --- |
| `app/translator.py` | `core/prompts.py` 中的总结与术语提取、直译（faithfulness）、反思意译（expressiveness）提示词；`core/_4_2_translate.py`、`core/translate_lines.py` 的分块翻译流程（约 600 字一块、前 3 行 / 后 2 行上下文、按块匹配术语、润色失败时保留直译） | 同时输出中文和英文以填充双语字段；新增中文校对提示词；术语同时带中英文译名；用户术语表优先；使用 DeepSeek 的 JSON 模式；去掉了配置文件和日志等依赖 |
| `app/segmenter.py` | `core/_3_2_split_meaning.py` 的长句拆分思路（在标点附近、尽量均分）和 `core/_5_split_sub.py` 的 `calc_len` 字符宽度思路 | 不使用 spaCy 和 LLM，改为根据 Whisper 的词级时间戳、标点和停顿重组句子并拆分长句 |

以上改动由本项目作者完成。
