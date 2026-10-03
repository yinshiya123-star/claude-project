#!/usr/bin/env bash
# DeepSeek 中英字幕生成器：macOS / Linux 一键启动
# 用法：在终端运行 ./start.sh（macOS 也可以双击「启动-Mac.command」）
set -u
cd "$(dirname "$0")"

URL="http://localhost:8000"
DEPS="import fastapi, uvicorn, multipart, openai, faster_whisper, dotenv, mutagen"

pause() { read -r -p "按回车键退出..." _ || true; }
fail() { echo; echo "[错误] $1"; echo; pause; exit 1; }

echo "============================================"
echo "       DeepSeek 中英字幕生成器"
echo "============================================"
echo

# ---- 1. 找到 Python 3.10+ ----
PY=""
for c in python3 python; do
    if command -v "$c" >/dev/null 2>&1 && "$c" -c 'import sys; sys.exit(sys.version_info < (3, 10))' 2>/dev/null; then
        PY="$c"
        break
    fi
done
if [ -z "$PY" ]; then
    if [ "$(uname)" = "Darwin" ]; then
        open "https://www.python.org/downloads/macos/" 2>/dev/null
        fail "没有找到 Python 3.10 或更新版本。请从刚打开的网页下载安装 Python 3.12，然后重新运行。"
    fi
    fail "没有找到 Python 3.10 或更新版本。Ubuntu/Debian 请运行：sudo apt install python3 python3-venv"
fi
echo "已找到 $("$PY" --version 2>&1)"

# ---- 2. 虚拟环境（只在第一次运行时创建）----
if [ ! -x .venv/bin/python ]; then
    echo "正在创建虚拟环境..."
    if ! "$PY" -m venv .venv; then
        rm -rf .venv
        fail "创建虚拟环境失败。Ubuntu/Debian 请先运行：sudo apt install python3-venv"
    fi
fi
VPY=.venv/bin/python

# ---- 3. 安装依赖（只在缺少时安装）----
if ! "$VPY" -c "$DEPS" >/dev/null 2>&1; then
    echo
    echo "第一次运行，正在安装依赖，需要几分钟，请耐心等待..."
    echo
    "$VPY" -m pip install -r requirements.txt -i https://mirrors.aliyun.com/pypi/simple/ \
        || { echo; echo "阿里云镜像安装失败，换清华镜像重试..."; "$VPY" -m pip install -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple; } \
        || { echo; echo "国内镜像安装失败，换官方源重试..."; "$VPY" -m pip install -r requirements.txt; } \
        || fail "依赖安装失败。请检查网络；如果开着代理软件，可以关掉后重试。"
fi

# ---- 4. 启动服务 ----
export HF_ENDPOINT="${HF_ENDPOINT:-https://hf-mirror.com}"
echo
echo "服务启动中，稍后会自动打开浏览器：$URL"
echo "第一次生成字幕时会下载语音识别模型（约 460MB），请耐心等待。"
echo "使用期间请不要关闭这个窗口；关闭窗口或按 Ctrl+C 即停止服务。"
echo
(
    sleep 4
    if command -v open >/dev/null 2>&1; then open "$URL"
    elif command -v xdg-open >/dev/null 2>&1; then xdg-open "$URL"
    fi
) >/dev/null 2>&1 &
"$VPY" -m uvicorn app.main:app --host 127.0.0.1 --port 8000
echo
echo "服务已停止。如果上面有报错（例如端口 8000 被占用），请复制给开发者。"
pause
