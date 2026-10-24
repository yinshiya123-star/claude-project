# SKGram

基于 TDLib（Telegram 官方客户端库）的第三方 Telegram 安卓客户端，界面采用蔚蓝档案风格的原创设计：晴空蓝渐变、光环徽记、斜切按钮与条纹背景（未使用任何游戏原画或素材）。

## 功能

- 登录：手机号 → 验证码 → 两步验证密码 → 邮箱验证（Telegram 要求时）
- 聊天列表：置顶、未读数、@提及、已读回执（✓/✓✓）、长按置顶/标为已读/复制 ID
- 快捷分类：全部 / 未读 / 私聊 / 群组 / 频道（不用手动建文件夹），聊天搜索
- 聊天：文字、图片、贴纸、视频/GIF 封面、文件、语音；回复、复制、删除、日期分隔
- **SKGram 增强**（官方客户端没有）：
  - 幽灵模式：不发送已读回执、不显示在线
  - 一键翻译消息（翻译成当前界面语言）
  - +1 复读
  - 双击消息快速回复
  - 显示聊天 / 用户 / 消息 ID
  - 媒体加载时立即显示模糊预览
- 语言：简体中文、繁體中文、한국어、日本語、English（设置里切换，或跟随系统）
- 主题：学园晴空、学园夜幕、樱花、跟随系统

## 速度相关的取舍

- 核心是 TDLib 原生库，开启本地消息/聊天数据库：重开应用时聊天列表直接从本地读出
- 应用启动时就初始化 TDLib，界面出现时数据已在加载
- 聊天列表在大量更新涌入时最多每 80ms 重排一次，不会卡顿
- 图片按显示尺寸降采样解码并缓存；release 包开启 R8 压缩优化
- 按 CPU 架构分包，arm64 包约 24MB

## 安装

大多数手机装 `SKGram-arm64-v8a.apk`；老手机装 `armeabi-v7a`；不确定就装 `universal`。需要 Android 8.0 及以上。

首次启动需要填写 Telegram API ID 和 API Hash：登录 <https://my.telegram.org> → API development tools → 创建应用即可获得。也可以构建时预置（见下）。

## 构建

需要 JDK 17+ 和 Android SDK（platform `android-37.0`）。

```bash
cd skgram
# 可选：预置 API 凭据，写进 local.properties（不会提交）
echo "tg.api.id=123456" >> local.properties
echo "tg.api.hash=0123456789abcdef0123456789abcdef" >> local.properties
./gradlew :app:assembleRelease
# 输出在 app/build/outputs/apk/release/
```

没配置签名密钥时 release 包用 debug 密钥签名，可以直接安装。正式发布请在 `local.properties` 或环境变量里配置 `skgram.keystore`、`skgram.keystore.password`、`skgram.key.alias`、`skgram.key.password`。

GitHub Actions 工作流 `.github/workflows/skgram.yml` 会自动构建 APK；手动运行并填写版本号会发布到 Releases。

## 目前还没有

推送通知、发送图片/文件、语音播放、视频播放、通话、秘密聊天、多账号。
