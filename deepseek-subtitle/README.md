# DeepSeek 中英字幕生成器

上传 MP3 / MP4 等音视频文件，自动生成**中英双语字幕**或**中文字幕**，在网页中预览、编辑，并下载字幕文件、带字幕的 MP3，或把字幕烧录进视频。

## 工作流程

字幕处理流程借鉴了高 star 开源项目 [VideoLingo](https://github.com/Huanshere/VideoLingo)（Apache-2.0，详见 [第三方声明](THIRD_PARTY_NOTICES.md)）：

```
音视频文件
  ──► faster-whisper 本地语音识别（词级时间戳，支持多国语言）
  ──► 按标点和停顿重组成完整句子，过长的句子在逗号 / 停顿处拆开
  ──► DeepSeek 通读全文：总结视频主题、提取人名和专业术语
  ──► 分块翻译（每块约 600 字，带前后文、主题和术语）：直译 → 反思 → 润色
  ──► 字幕
```

> DeepSeek API 只支持文本，不能直接处理音频，所以先用开源的 Whisper 模型在本地把语音转成带时间轴的文字，再交给 DeepSeek 翻译。和 VideoLingo 不同，这里没有使用 WhisperX、spaCy 和 PyTorch，安装包小很多，Windows 上也容易装。

## 功能

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
| `WHISPER_MODEL` | `small` | `tiny` / `base` / `small` / `medium` / `large-v3`，越大越准越慢 |
| `WHISPER_DEVICE` | `cpu` | `cpu` / `cuda` / `auto`。用 NVIDIA 显卡加速需另装 CUDA 12 和 cuDNN 9，GPU 出错时会自动退回 CPU |
| `MAX_UPLOAD_MB` | `500` | 上传大小上限 |
| `SUBTITLE_FONT` | 自动查找 | 烧录字幕用的字体文件路径，例如 `C:/Windows/Fonts/simhei.ttf` |

## API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/jobs` | 上传文件（表单字段 `file`；可选 `target=bilingual/zh`、`language`、`api_key`、`reflect=1/0`（精翻）、`terms`（术语表，每行 `原文=译文`）），返回任务 `id` |
| `GET` | `/api/jobs/{id}` | 查询进度与字幕内容 |
| `PUT` | `/api/jobs/{id}/segments` | 保存编辑后的字幕 |
| `GET` | `/api/jobs/{id}/subtitle.srt`、`.vtt` 或 `.lrc`，参数 `mode=bilingual/zh/en/orig` | 获取字幕文件（加 `&download=true` 下载） |
| `GET` | `/api/jobs/{id}/export.mp3?mode=...` | 下载带字幕（ID3 歌词）的 MP3 |
| `POST` | `/api/jobs/{id}/burn`，JSON `{"mode": "bilingual"}` | 开始把字幕烧录进视频；进度在任务详情的 `burn` 字段 |
| `GET` | `/api/jobs/{id}/burned.mp4` | 下载烧录好的视频 |
| `GET` | `/api/jobs/{id}/media` | 原始音视频 |

## 项目结构

```
app/
  main.py         FastAPI 服务、任务队列、接口
  transcriber.py  音频解码、faster-whisper 语音识别（GPU 出错自动退回 CPU）
  segmenter.py    用词级时间戳重组句子、拆分长句（借鉴 VideoLingo）
  translator.py   DeepSeek 总结术语、分块两步翻译、中文校对（借鉴 VideoLingo）
  subtitles.py    SRT / VTT / LRC 生成
  export.py       导出带字幕（ID3 歌词）的 MP3
  burn.py         把字幕烧录进视频（PyAV 解码 / 编码 + Pillow 绘制字幕）
  media.py        音频重新编码（烧录和导出 MP3 共用）
static/           网页界面（原生 HTML/CSS/JS）
tests/            单元测试（pytest）
启动.bat          Windows 一键启动
启动-Mac.command  macOS 一键启动
start.sh          macOS / Linux 一键启动
```

## 说明

- 任务保存在内存中，服务重启后历史任务会丢失；上传的文件保存在 `data/uploads/`。
- 为避免占满 CPU/GPU，同一时间只处理一个任务，其余排队。
- 浏览器能否播放视频取决于编码格式（如 H.265 的 mp4 部分浏览器不支持），但不影响字幕生成和下载。
