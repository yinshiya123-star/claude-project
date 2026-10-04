@echo off
chcp 65001 >nul
title DeepSeek 中英字幕生成器
cd /d "%~dp0"

echo ============================================
echo        DeepSeek 中英字幕生成器
echo ============================================
echo.

rem ---- 1. 检查 Python ----
python --version >nul 2>&1
if errorlevel 1 (
    echo [错误] 没有找到 Python。
    echo 请安装 Python 3.12，安装时一定要勾选 "Add python.exe to PATH"。
    echo 正在打开下载页面...
    start "" https://www.python.org/downloads/release/python-3128/
    goto :fail
)
for /f "delims=" %%v in ('python --version 2^>^&1') do echo 已找到 %%v

rem ---- 2. 检查并安装依赖（只在第一次运行时安装）----
python -c "import fastapi, uvicorn, multipart, openai, faster_whisper, dotenv, mutagen, PIL, edge_tts, rapidocr" >nul 2>&1
if not errorlevel 1 goto :run

echo.
echo 第一次运行，正在安装依赖，需要几分钟，请耐心等待...
echo.
rem 清掉本窗口的代理设置，避免 SSL 报错
set HTTP_PROXY=
set HTTPS_PROXY=
set http_proxy=
set https_proxy=

python -m pip install -r requirements.txt -i http://mirrors.aliyun.com/pypi/simple/ --trusted-host mirrors.aliyun.com
if not errorlevel 1 goto :run

echo.
echo 阿里云镜像安装失败，换清华镜像重试...
python -m pip install -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple
if not errorlevel 1 goto :run

echo.
echo [错误] 依赖安装失败。可以尝试：
echo   1. 关闭代理或加速器软件（如 Clash、v2rayN），然后重新双击本文件；
echo   2. 如果上面的报错里有 av、ctranslate2 或 building wheel，
echo      说明 Python 版本太新，请卸载后改装 Python 3.12。
goto :fail

rem ---- 3. 启动服务 ----
:run
if not defined HF_ENDPOINT set HF_ENDPOINT=https://hf-mirror.com
echo.
echo 服务启动中，稍后会自动打开浏览器：http://localhost:8000
echo 第一次生成字幕时会下载语音识别模型（约 460MB），请耐心等待。
echo 使用期间请不要关闭这个窗口；关闭窗口即停止服务。
echo.
start "" /min cmd /c "timeout /t 4 /nobreak >nul && explorer http://localhost:8000"
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
echo.
echo 服务已停止。如果上面有报错（例如端口 8000 被占用），请截图或复制给开发者。

:fail
echo.
pause
