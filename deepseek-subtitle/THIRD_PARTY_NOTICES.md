# 第三方声明 / Third-party notices

## VideoLingo

- 项目：https://github.com/Huanshere/VideoLingo
- 许可证：Apache License 2.0（全文见 [`licenses/VideoLingo-LICENSE.txt`](licenses/VideoLingo-LICENSE.txt)）

本项目借鉴了 VideoLingo 的字幕处理流程，部分内容改编自它的源代码：

| 本项目文件 | 借鉴 / 改编自 VideoLingo | 主要改动 |
| --- | --- | --- |
| `app/translator.py` | `core/prompts.py` 中的总结与术语提取、直译（faithfulness）、反思意译（expressiveness）提示词；`core/_4_2_translate.py`、`core/translate_lines.py` 的分块翻译流程（约 600 字一块、前 3 行 / 后 2 行上下文、按块匹配术语、润色失败时保留直译） | 同时输出中文和英文以填充双语字段；新增中文校对提示词；术语同时带中英文译名；用户术语表优先；使用 DeepSeek 的 JSON 模式；去掉了配置文件和日志等依赖 |
| `app/dub.py` | `core/_8_1_audio_task.py` ~ `core/_12_dub_to_vid.py` 的配音流程（逐句合成、按字幕时长调整语速、拼接到时间轴、与视频合并） | 使用 edge-tts；用 TTS 语速参数代替音频变速；不使用 Demucs 人声分离，而是把原声按设定音量保留；视频流直接复制 |
| `app/segmenter.py` | `core/_3_2_split_meaning.py` 的长句拆分思路（在标点附近、尽量均分）和 `core/_5_split_sub.py` 的 `calc_len` 字符宽度思路 | 不使用 spaCy 和 LLM，改为根据 Whisper 的词级时间戳、标点和停顿重组句子并拆分长句 |

以上改动由本项目作者完成。

## 其他依赖

- [RapidOCR](https://github.com/RapidAI/RapidOCR)（Apache-2.0）及其附带的 PaddleOCR 模型：图片文字识别
- [edge-tts](https://github.com/rany2/edge-tts)（LGPL-3.0，作为独立的 Python 包安装和调用，未修改）：AI 配音
- [faster-whisper](https://github.com/SYSTRAN/faster-whisper)（MIT）、[PyAV](https://github.com/PyAV-Org/PyAV)（BSD）、[mutagen](https://github.com/quodlibet/mutagen)（GPL-2.0+，作为独立的 Python 包安装和调用）等通过 pip 安装，许可证见各自项目
