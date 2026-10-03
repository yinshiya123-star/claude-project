# DeepSeek 中英字幕生成器

上传 MP3 / MP4 等音视频文件，自动生成**中英双语字幕**，并在网页中预览、编辑和下载。

## 工作流程

```
音视频文件 ──► faster-whisper 本地语音识别（带时间轴） ──► DeepSeek API 翻译/校对 ──► 中英双语字幕
```

> DeepSeek API 只支持文本，不能直接处理音频，所以先用开源的 Whisper 模型在本地把语音转成带时间轴的文字，再交给 DeepSeek 结合上下文翻译成中文和英文（同时修正明显的识别错误）。

## 功能

- 拖拽上传 mp3、mp4、m4a、wav、mov、mkv 等格式，显示上传和处理进度
- 自动检测原始语言（也可手动指定中文 / 英文 / 日语 / 韩语）
- 网页内播放器实时显示字幕，可切换 **双语 / 中文 / 英文**
- 字幕列表：点击时间跳转、播放时自动高亮当前行、可直接编辑文本
- 下载 双语 SRT、中文 SRT、英文 SRT、双语 VTT

## 快速开始

### Windows：双击启动

1. 安装 [Python 3.12](https://www.python.org/downloads/release/python-3128/)，安装时勾选 **Add python.exe to PATH**；
2. 双击 `deepseek-subtitle` 文件夹里的 **`启动.bat`**。

第一次运行会自动安装依赖（使用国内镜像），然后启动服务并打开浏览器。以后每次双击就能直接用。使用期间不要关闭黑色窗口。

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
| `DEEPSEEK_API_KEY` | – | DeepSeek API Key；不配置时需在网页中填写 |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | API 地址 |
| `DEEPSEEK_MODEL` | `deepseek-chat` | 翻译用的模型 |
| `WHISPER_MODEL` | `small` | `tiny` / `base` / `small` / `medium` / `large-v3`，越大越准越慢 |
| `WHISPER_DEVICE` | `auto` | `cpu` / `cuda` / `auto` |
| `MAX_UPLOAD_MB` | `500` | 上传大小上限 |

## API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/jobs` | 上传文件（表单字段 `file`，可选 `language`、`api_key`），返回任务 `id` |
| `GET` | `/api/jobs/{id}` | 查询进度与字幕内容 |
| `PUT` | `/api/jobs/{id}/segments` | 保存编辑后的字幕 |
| `GET` | `/api/jobs/{id}/subtitle.srt` 或 `.vtt`，参数 `mode=bilingual/zh/en` | 获取字幕文件（加 `&download=true` 下载） |
| `GET` | `/api/jobs/{id}/media` | 原始音视频 |

## 项目结构

```
app/
  main.py         FastAPI 服务、任务队列、接口
  transcriber.py  faster-whisper 语音识别
  translator.py   DeepSeek 批量翻译（JSON 输出、带上下文、失败重试）
  subtitles.py    SRT / VTT 生成
static/           网页界面（原生 HTML/CSS/JS）
tests/            单元测试（pytest）
```

## 说明

- 任务保存在内存中，服务重启后历史任务会丢失；上传的文件保存在 `data/uploads/`。
- 为避免占满 CPU/GPU，同一时间只处理一个任务，其余排队。
- 浏览器能否播放视频取决于编码格式（如 H.265 的 mp4 部分浏览器不支持），但不影响字幕生成和下载。
