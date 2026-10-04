# DeepSeek 字幕工坊

上传 MP3 / MP4 等音视频文件，自动生成 **Netflix 级时间轴**的**中英双语字幕**或**中文字幕**，在网页中预览、编辑，下载字幕文件、带字幕的 MP3，把字幕烧录进视频，或者生成 **AI 配音**；还能**翻译图片里的外语文字**。

## 工作流程

字幕处理流程借鉴了高 star 开源项目 [VideoLingo](https://github.com/Huanshere/VideoLingo)（Apache-2.0，详见 [第三方声明](THIRD_PARTY_NOTICES.md)）：

```
音视频文件
  ──► faster-whisper 本地语音识别（词级时间戳，支持多国语言，术语表作为热词）
  ──► 按标点和停顿重组成完整句子，过长的句子在逗号 / 停顿处拆开
  ──► DeepSeek 通读全文：总结视频主题、提取人名和专业术语
  ──► DeepSeek 校正识别文本（听错的词、同音字、标点）
  ──► 分块翻译（每块约 600 字，带前后文、主题和术语）：直译 → 反思 → 润色
  ──► 按 Netflix 字幕规范调整时间轴和折行
  ──► 字幕 / 带字幕 MP3 / 烧录视频 / AI 配音
```

> DeepSeek API 只支持文本，不能直接处理音频，所以先用开源的 Whisper 模型在本地把语音转成带时间轴的文字，再交给 DeepSeek 翻译。和 VideoLingo 不同，这里没有使用 WhisperX、spaCy 和 PyTorch，安装包小很多，Windows 上也容易装。

## 功能

- **Netflix 级时间轴**：按 Netflix 字幕规范处理——每条至少 5/6 秒、最长 7 秒；按阅读速度（英文每秒 17 字符、中文每秒 9 字）延长显示时间；相邻字幕至少间隔 2 帧，小于 0.5 秒的空隙收拢成 2 帧；时间对齐到视频帧；单语字幕自动折行（英文每行 42 字符、中文每行 16 字，最多两行，上短下长）
- **识别更准确**：可选识别模型（快速 small / 均衡 medium / 最准 large-v3-turbo）；术语表作为热词提示给 Whisper；关闭"沿用上文"避免 Whisper 重复和幻听；DeepSeek 结合上下文校正识别文本
- **AI 配音（尽量像原声）**：不用自己挑音色。先用声纹区分视频里的每个说话人（音调相近的两个人也能分开），然后：
  - **克隆原声**（默认，免费、离线）：用 GitHub 上的开源模型 [ZipVoice](https://github.com/k2-fsa/ZipVoice)，从视频里截取每个人约 8 秒的原声，让他用自己的声音说译文（跨语言也可以）；第一次使用自动下载约 160 MB 模型；
  - 或者 **匹配微软神经语音**（更快，需联网）：为每个人挑最接近的音色，调到他的音高和语速，每句跟随原句的语调；
  - 同一个人连着说的几句一口气合成，不在半句话中间停顿；音量跟随原句；太长的句子自动加快语速；
  - 有人说话时原声静音，不说话时（音乐、环境声）原声保持不变；视频画面原样保留（不重新编码），纯音频输出 MP3，也可以同时烧录字幕
- **画面文字翻译**：上传时勾选「同时翻译视频画面里的文字」（默认开启），字幕生成后自动识别视频画面里的文字（路牌、标题、PPT、外语硬字幕……），自动跟踪每段文字出现和消失的时间，翻译成中文 / 英文 / 原文 + 中文 / 中英双语；可以叠加在播放器上预览、导出为字幕（显示在画面上方），烧录时可选「替换原文」（擦掉原文、按原文颜色写上译文）或「在下方标注译文」。没有语音的视频也能用
- **批量处理**：一次选择或拖入多个视频，按顺序排队处理；队列里能看到每个文件的进度和排队位置，可随时取消；处理完可以逐个查看，也可以一键打包下载全部字幕（ZIP）或全部烧录
- **图片翻译**：上传菜单、路牌、海报、截图等图片（可多张），本地 OCR（RapidOCR / PaddleOCR 模型）识别文字，DeepSeek 校正、翻译成中文 / 英文 / 原文 + 中文 / 中英双语，并生成译图：单语模式把原文擦掉、按原文颜色写上译文，双语模式在原文下方标注译文。默认支持中文、英文、日文，韩文、法德西意葡等拉丁字母语言、俄文、泰文、希腊文、阿拉伯文、印地文首次使用时自动下载模型
- **精美界面**：分步进度、播放器与编辑并排、功能分页、深色模式、手机适配
- **断句更自然**：不再沿用 Whisper 随意切分的片段，而是根据词级时间戳重组成完整句子，长句在逗号或停顿处拆开
- **精翻（翻译 → 反思 → 润色）**：参考 VideoLingo 的三步翻译，先忠实直译，再逐句反思并改写成自然的表达；可在页面上关掉以节省时间和费用
- **主题与术语**：翻译前先让 DeepSeek 总结视频主题、提取人名和专业术语，保证全片译名一致；也可以在页面上填写自己的术语表（如 `DeepSeek=深度求索`），优先使用
- **中英双语**：中文转双语、英文转双语，日语、韩语、法语等外语也能转成中英双语
- **仅中文**：中文音视频转中文字幕。填写 DeepSeek Key 后会校对错别字和标点，不填也能用
- **自动识别多国语言**：不用手动选择语言；同一个视频里混着几种语言也能逐段识别。也可以手动指定 20 种常用语言
- 外语视频额外提供 **原文 + 中文** 字幕
- 网页内播放器实时显示字幕，可切换字幕类型；字幕列表可点击跳转、直接编辑
- 下载 **SRT / VTT 字幕**、**LRC 歌词**，以及 **带字幕 MP3**：字幕作为歌词写进 MP3，手机和音乐播放器播放时可显示；上传视频会先转成 MP3
- **烧录字幕到视频**：把字幕直接画进画面（硬字幕），导出 MP4，任何播放器和视频网站都能看到。纯音频会生成黑底视频；手机竖拍的视频会按正确方向处理。使用系统自带的中文字体（Windows 微软雅黑、macOS 苹方、Linux Noto CJK / 文泉驿），不需要另装 ffmpeg。1080p 视频在 4 核 CPU 上大约是实时速度（1 分钟视频约 1 分钟）

## 快速开始

最简单的方式：在 [Releases](https://github.com/yinshiya123-star/claude-project/releases) 下载最新的 `deepseek-subtitle-*.zip`，解压后按你的系统双击启动脚本。第一次运行会自动安装依赖（优先使用国内镜像），然后启动服务并打开浏览器；以后每次直接启动即可。使用期间不要关闭命令行窗口。

### Windows

1. 安装 [Python 3.12](https://www.python.org/downloads/release/python-3128/)，安装时勾选 **Add python.exe to PATH**；
2. 双击 **`启动.bat`**。

### macOS

1. 安装 [Python 3.12](https://www.python.org/downloads/macos/)；
2. 双击 **`启动-Mac.command`**。第一次如果提示"无法验证开发者"，请右键点击它 → 打开 → 打开。

### Linux

```bash
sudo apt install python3 python3-venv   # Ubuntu / Debian，没装过才需要
./start.sh
```

### 手动启动

需要 Python 3.10+。

```bash
python -m venv .venv
source .venv/bin/activate        # Windows: .venv\Scripts\activate
pip install -r requirements.txt

cp .env.example .env             # 填入 DEEPSEEK_API_KEY（也可以在网页里填写）
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

打开 http://localhost:8000 即可使用。

首次运行时会自动从 Hugging Face 下载 Whisper 模型（`small` 约 460 MB）。国内网络可以设置镜像：

```bash
export HF_ENDPOINT=https://hf-mirror.com
```

## 配置项（`.env`）

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DEEPSEEK_API_KEY` | – | DeepSeek API Key；不配置时需在网页中填写（"仅中文"模式可不填） |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | API 地址 |
| `DEEPSEEK_MODEL` | `deepseek-chat` | 翻译用的模型 |
| `WHISPER_MODEL` | `small` | 默认识别模型（网页上可按任务选择 small / medium / large-v3-turbo） |
| `WHISPER_DEVICE` | `cpu` | `cpu` / `cuda` / `auto`。用 NVIDIA 显卡加速需另装 CUDA 12 和 cuDNN 9，GPU 出错时会自动退回 CPU |
| `MAX_UPLOAD_MB` | `500` | 上传大小上限 |
| `SUBTITLE_FONT` | 自动查找 | 烧录字幕用的字体文件路径，例如 `C:/Windows/Fonts/simhei.ttf` |

## API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/jobs` | 上传文件（表单字段 `file`；可选 `target=bilingual/zh`、`language`、`api_key`、`model`、`reflect=1/0`（精翻）、`correct=1/0`（AI 校正）、`terms`（术语表，每行 `原文=译文`）），返回任务 `id` |
| `GET` | `/api/jobs/{id}` | 查询进度与字幕内容 |
| `PUT` | `/api/jobs/{id}/segments` | 保存编辑后的字幕 |
| `GET` | `/api/jobs/{id}/subtitle.srt`、`.vtt` 或 `.lrc`，参数 `mode=bilingual/zh/en/orig` | 获取字幕文件（加 `&download=true` 下载） |
| `GET` | `/api/jobs/{id}/export.mp3?mode=...` | 下载带字幕（ID3 歌词）的 MP3 |
| `POST` | `/api/jobs/{id}/burn`，JSON `{"mode": "bilingual"}` | 开始把字幕烧录进视频；进度在任务详情的 `burn` 字段 |
| `GET` | `/api/jobs/{id}/burned.mp4` | 下载烧录好的视频 |
| `GET` | `/api/voices` | 配音音色列表 |
| `POST` | `/api/jobs/{id}/dub`，JSON `{"lang": "zh", "bg_volume": 0, "burn_mode": null, "engine": "clone"}`（engine：clone 克隆原声 / edge 微软神经语音；bg_volume：说话时保留的原声音量，不说话时原声不变） | 开始 AI 配音（自动匹配说话人的声音；也可传 `voice` 指定统一音色）；进度和说话人匹配结果在任务详情的 `dub` 字段 |
| `GET` | `/api/jobs/{id}/dubbed` | 下载配音结果（MP4 或 MP3） |
| `POST` | `/api/images`（表单 `file`、`target=zh/en/orig_zh/zh_en`、`ocr_lang`、`api_key`、`terms`） | 翻译图片里的文字，返回 `id` |
| `GET` | `/api/images/{id}`、`/translated.png`、`/text.txt`、`/source` | 查询结果、下载译图 / 文字、原图 |
| `POST` | `/api/jobs/{id}/screen`，JSON `{"target": "orig_zh", "ocr_lang": "auto", "interval": 1.0, "api_key": "..."}` | 识别并翻译视频画面里的文字；结果在任务详情的 `screen` 字段 |
| `GET` | `/api/jobs/{id}/screen.srt` | 下载画面文字字幕 |
| `POST` | `/api/jobs/{id}/burn` 的可选字段 `screen=replace/label`、`subtitles=true/false` | 烧录时同时翻译画面文字 |
| `POST` | `/api/jobs/{id}/cancel` | 取消排队中或正在处理的任务 |
| `GET` | `/api/jobs?ids=a,b,c` | 批量查询任务状态（含排队位置） |
| `POST` | `/api/batch/subtitles.zip`、`/api/batch/burn`，JSON `{"ids": [...], "mode": "bilingual", "fmt": "srt"}` | 打包下载字幕、批量烧录 |
| `GET` | `/api/jobs/{id}/media` | 原始音视频 |

## 项目结构

```
app/
  main.py         FastAPI 服务、任务队列、接口
  transcriber.py  音频解码、faster-whisper 语音识别（GPU 出错自动退回 CPU）
  segmenter.py    用词级时间戳重组句子、拆分长句（借鉴 VideoLingo）
  timing.py       Netflix 字幕规范：时长、阅读速度、间隔、帧对齐、折行
  translator.py   DeepSeek 总结术语、分块两步翻译、中文校对（借鉴 VideoLingo）
  subtitles.py    SRT / VTT / LRC 生成
  export.py       导出带字幕（ID3 歌词）的 MP3
  burn.py         把字幕烧录进视频（PyAV 解码 / 编码 + Pillow 绘制字幕）
  media.py        音频重新编码（烧录、导出、配音共用）
  dub.py          AI 配音（借鉴 VideoLingo 的配音流程）
  clone.py        声音克隆（ZipVoice，本地运行）
  speakers.py     声纹识别：区分说话人
  voices.py       分析原声：音高、响度、区分说话人，自动匹配配音音色
  image_translate.py  图片翻译（RapidOCR 识别 + DeepSeek 翻译 + 绘制译图）
  screen_text.py  视频画面文字：抽帧识别、跟踪时间、翻译、烧录叠加
static/           网页界面（原生 HTML/CSS/JS）
tests/            单元测试（pytest）
启动.bat          Windows 一键启动
启动-Mac.command  macOS 一键启动
start.sh          macOS / Linux 一键启动
```

## 说明

- 任务保存在内存中，服务重启后历史任务会丢失；上传的文件保存在 `data/uploads/`。
- 克隆原声和声纹识别第一次使用时要从 GitHub 下载模型（约 190 MB，GitHub 不通时自动尝试镜像），之后离线运行；「微软神经语音」配音需要能访问微软 Edge 语音服务（speech.platform.bing.com）；图片翻译首次使用韩文等语言时需要从 ModelScope 下载识别模型。
- 为避免占满 CPU/GPU，同一时间只处理一个任务，其余排队（页面上会显示前面还有几个任务，可以取消）。第一次使用某个识别模型时会先下载模型，页面会提示，命令行窗口里能看到下载进度。
- 升级到新版本后如果页面显示异常，按 Ctrl + F5 强制刷新一次（新版本已设置为每次检查更新，之后不会再出现）。
- 浏览器能否播放视频取决于编码格式（如 H.265 的 mp4 部分浏览器不支持），但不影响字幕生成和下载。
