# SKGram

基于 TDLib（Telegram 官方客户端库）的第三方 Telegram 客户端，支持 **Android、Windows、macOS、Linux**。界面采用蔚蓝档案风格的原创设计：晴空蓝渐变、光环徽记、斜切按钮与条纹背景（未使用任何游戏原画或素材）。

## 下载

在 GitHub Releases 页面下载：

| 设备 | 文件 |
|---|---|
| 安卓手机（大多数） | `SKGram-*-android-arm64-v8a.apk` |
| 安卓老手机 | `SKGram-*-android-armeabi-v7a.apk` |
| 不确定 | `SKGram-*-android-universal.apk` |
| Windows | `SKGram-*-windows-x64.exe` 或 `.msi` |
| Mac（Apple 芯片） | `SKGram-*-macos-arm64.dmg` |
| Mac（Intel） | `SKGram-*-macos-x64.dmg` |
| Linux | `SKGram-*-x86_64.AppImage` 或 `.deb` |

首次启动需要填写 Telegram API ID 和 API Hash：登录 <https://my.telegram.org> → API development tools → 创建应用即可获得。

**发布者可以替用户省掉这一步**：自己申请一次，把 API ID 和 API Hash 填进 GitHub 仓库的 Secrets（`TG_API_ID`、`TG_API_HASH`），之后 CI 构建的所有安装包都会内置，用户打开直接到输入手机号这一步。

my.telegram.org 报「尝试次数过多」或只显示「ERROR」时：等几个小时（常常要一天），关闭 VPN/代理，用手机浏览器 + 移动数据试；标题和简称用纯英文、不含「Telegram」，URL 留空。

macOS 包没有 Apple 签名，第一次打开请在「访达」里右键 →「打开」。Windows 若弹出 SmartScreen，点「更多信息」→「仍要运行」。

## 功能

- 登录：手机号 → 验证码 → 两步验证 → 邮箱验证（Telegram 要求时）
- **多账号**：可同时登录多个账号，全部在线收消息；点标题或在设置里切换
- 聊天列表：置顶、未读、@提及、已读回执，快捷分类（全部 / 未读 / 私聊 / 群组 / 频道 / 私密）
- 聊天：文字、图片、贴纸、视频、GIF、语音、音频、文件、通话记录；回复、复制、删除
- **发送图片、视频、文件**；安卓上可**录制并发送语音**（Android 10+）
- **播放**：安卓内置视频播放器和语音/音频播放；桌面版用系统默认播放器打开
- **私密聊天**（端到端加密），新建聊天页可从联系人发起
- **搜索并加入公开群组和频道**
- **语音通话**：私聊顶部的电话按钮发起；来电全屏显示，可接听/拒绝，支持静音、扬声器（安卓）、端到端加密表情校验。通话引擎是 [NTgCalls](https://github.com/pytgcalls/ntgcalls)（LGPL-3.0，WebRTC），Telegram 的信令和密钥交换由 TDLib 完成
- **通知**：遵循每个聊天的免打扰设置；安卓可选「后台保持连接」以即时收到消息；桌面版用系统托盘通知
- SKGram 增强：幽灵模式（不发已读、不显示在线）、一键翻译、+1 复读、双击回复、显示 ID、媒体加载时即时模糊预览
- 语言：简体中文、繁體中文、한국어、日本語、English
- 主题：学园晴空、学园夜幕、樱花、跟随系统

## 还没有

- **视频通话**（目前只有语音）。
- **Intel 芯片的 Mac 不能通话**：NTgCalls 没有提供这个平台的版本。来电仍会显示，可以拒绝。
- 推送不走 Google FCM（第三方客户端拿不到 Telegram 的推送通道），安卓靠「后台保持连接」实现即时通知。
- 桌面版录音。

## 项目结构

```
shared/       Kotlin Multiplatform：全部界面和逻辑（Compose Multiplatform）
  commonMain/   两端共用
  androidMain/  通知、后台服务、ExoPlayer、选图、录音
  desktopMain/  托盘通知、文件对话框、系统播放器
androidApp/   安卓应用外壳
desktopApp/   桌面应用外壳 + 安装包配置
tools/
  strings.py        五种语言的界面文字表（改完运行它生成 Strings.kt）
  icons.py          生成桌面图标
  make-appimage.sh  把 Linux 程序打包成单个 AppImage
```

## 构建

需要 JDK 17+；安卓还需要 Android SDK（platform `android-37.0`）。版本号在根目录 `build.gradle.kts` 里统一设置。

```bash
./gradlew :androidApp:assembleRelease      # APK → androidApp/build/outputs/apk/release/
./gradlew :desktopApp:run                  # 直接运行桌面版
./gradlew :desktopApp:packageExe           # Windows（需在 Windows 上）
./gradlew :desktopApp:packageDmg           # macOS（需在 Mac 上）
./gradlew :desktopApp:packageAppImage && tools/make-appimage.sh 1.1.0   # Linux
```

安装包只能在对应系统上构建，所以 GitHub Actions 会在 Windows、macOS、Linux 三种机器上分别构建：

- 推送 `skgram/` 的改动：构建全部安装包，作为 Artifacts 上传
- 推送 `skgram-v*` 标签（如 `skgram-v1.1.0`）：构建并发布到 Releases

可选：在 `local.properties` 或仓库 Secrets 里预置 `tg.api.id` / `tg.api.hash`（`TG_API_ID` / `TG_API_HASH`）；配置 `skgram.keystore` 等签名参数（见 `.github/workflows/skgram-build.yml`）。没配签名时 APK 用 debug 密钥签名，可以直接安装，但不同机器构建的版本不能互相覆盖安装。
