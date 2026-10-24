# 一键木偶打光

Android App（Kotlin，最低 Android 8.0 / API 26）。通过 **无障碍服务（AccessibilityService）** 自动操作手机上已安装的 **NV MAX** 和 **Motion Ninja**，完成「绿幕抠像 → 3D 摄影机分层 → 打光调色 → 反转/饱和度关键帧 → 摇晃镜像 → 导出 → Motion Ninja 推镜运镜 → 保存相册」整套流程。

用户只需要：选背景图 → 选绿幕素材 → 点「一键制作」。

## 两种控制方式

主界面顶部可切换：

| | 无障碍模式 | Shizuku 模式（adb 权限） |
|---|---|---|
| 需要 | 开启无障碍服务 | 安装 [Shizuku](https://shizuku.rikka.app/zh-hans/download/)，用「无线调试」激活；悬浮窗权限 |
| 系统版本 | Android 8+ | Android 11+（或用电脑 USB 激活） |
| 查找控件 | AccessibilityNodeInfo | `uiautomator dump` |
| 点击/拖动/缩放 | dispatchGesture | shell 进程直接注入触摸事件（失败时退回 `input` 命令） |
| 输入文字 | ACTION_SET_TEXT | 全选 + `input text`；中文用剪贴板 + 粘贴键 |
| 截图检测 | 无障碍截图 / 录屏授权 | `screencap` |
| 录制 | 无障碍点击事件 + 手动坐标 | `getevent` 读取每次触摸 + 匹配被点的控件 |
| 注意 | 小米等系统可能清理后台、拦截后台打开 App | 小米需在开发者选项打开「USB 调试（安全设置）」；手机重启后要在 Shizuku 里重新启动 |

两种模式使用同一套 JSON 步骤脚本。Shizuku 模式下，如果 App 的预览画面一直在动，`uiautomator dump` 可能拿不到控件，这时会使用脚本里的坐标兜底。

## 下载

- 编译好的 APK：[`release/一键木偶打光-v1.0.0.apk`](release/)（debug 签名，可直接安装）
- 每次推送代码，GitHub Actions 也会自动编译 APK（Actions → Build APK → Artifacts）

## 功能

| 功能 | 说明 |
|---|---|
| 极简界面 | 只需选择背景图、绿幕素材（图片或视频），一个「一键制作」按钮 |
| 高级设置 | 光源颜色（预设 + 自定义）、关键帧时间点（默认 1.5 秒）、摇晃强度、饱和度渐变开关、手动指定包名 |
| 首次引导 | 引导开启无障碍、悬浮窗权限；自动检测 NV MAX / Motion Ninja，未安装时跳转应用商店 |
| 运行悬浮窗 | 实时显示「第 N/总 步」、阶段、当前动作；可暂停/继续/停止；出错时显示是哪一步、原因，可重试/跳过 |
| 控件查找 | 依次按文字、contentDescription、viewId 查找；找不到时用坐标（按屏幕比例换算）`dispatchGesture` 点击 |
| 校验与重试 | 每步执行后等待加载，可配置 `verify` 检测目标控件；失败自动重试 3 次，仍失败则暂停并提示 |
| 可配置脚本 | 所有步骤是 JSON（动作类型、目标文字/坐标、等待时间），支持变量、表达式、宏，App 内可编辑/导入/导出 |
| 录制模式 | 手动操作一遍，自动记录点击的控件（文字/描述/viewId/序号）和坐标、长按、输入，生成脚本；预览画面等自绘区域可用「坐标点击」「拖动」记录 |
| 手势 | 拖动滑块、拖动图层、拖时间轴用 `dispatchGesture`（按住→移动→停留，避免惯性）；双指缩放；输入参数值用 `ACTION_SET_TEXT`，进度条用 `ACTION_SET_PROGRESS` |
| 抠像残留检测 | 抠像后截图统计画面中「绿幕绿」像素占比，超过阈值自动调高容差再试，最多 3 次（Android 11+ 无障碍截图；Android 8~10 使用录屏授权） |
| 素材传递 | 素材自动复制到相册最新位置；NV MAX 导出后自动在相册/下载目录找到最新视频（等文件写完），再交给 Motion Ninja |
| 完成通知 | 成品保存到相册后弹出「制作完成」通知，点击即可播放 |

## 默认流程（`app/src/main/assets/scripts/default_flow.json`）

1. **准备**：把素材放进相册（绿幕素材 = 最新第 1 项，背景 = 第 2 项）
2. **NV MAX 准备素材**：新建项目 → 导入背景、绿幕素材 → 绿幕抠像（吸管取绿 + 容差）→ 截图检测残留绿色并自动调高容差 → 红蓝修正 = 1、Alpha = 0 → 角色拖到居中偏下 → 添加 3D 摄影机并复制成 3 个 → 背景拖入第 3 个、角色拖入第 2 个 → 复制第 2 个放入第 1 个 → 第 1 个里的摄影机再复制一份
3. **调色与打光**：填充颜色（绿色）→ 拖动露出侧光 → 第 1 个摄影机加绿幕参数 → 搜「光」点「灰光」→ 搜「缩光」点「柔光」，颜色 = 光源颜色
4. **组与后期**：添加组并把三个摄影机按从左到右放入 → 第 2 个摄影机加四色渐变（颜色一二 = 光源色、三四 = 黑色，正片叠底）→ 组加「反转」，在关键帧时间点打关键帧（只开「与原始图像混合」），参数拉为 1 → 基本矫正饱和度开头 0、关键帧时间点 0.5（可关）→ 摇晃强度 → 截断改镜像 → 导出
5. **Motion Ninja 运镜**：新建并导入刚导出的视频 → 在关键帧时间点加标记 → 开头、标记处各打一个关键帧 → 回到第一个关键帧放大 120% 并略微偏移 → 导出 → 等待保存到相册 → 通知「制作完成」

## ⚠️ 首次使用前请校准

NV MAX 和 Motion Ninja 没有公开的自动化接口，**默认脚本中的控件文字和兜底坐标是按常见写法和 1080×2400 竖屏估计的**，不同版本、语言、屏幕比例下界面可能不同。建议首次使用时：

1. 先运行一次，看悬浮窗提示在哪一步失败（日志在「脚本 / 录制 → 运行日志」）；
2. 用「录制模式」把失败的那段操作手动做一遍，参照生成的脚本（控件文字、viewId、坐标）修改默认脚本里对应的步骤或宏；
3. 也可以直接用录制生成的完整脚本作为当前脚本。

脚本格式详见 [docs/SCRIPT_FORMAT.md](docs/SCRIPT_FORMAT.md)。

## 工程结构

```
app/src/main/java/com/puppetlight/oneclick/
├── ui/        MainActivity（主界面/权限引导/一键制作）、SettingsActivity（高级设置）、
│              ScriptManagerActivity（脚本管理/录制入口/日志）、ScriptEditorActivity（JSON 编辑）
├── service/   PuppetAccessibilityService（无障碍服务）、RunOverlay（进度悬浮窗）、
│              ProjectionService（Android 8~10 截图）、Notifier（通知）
├── engine/    ScriptEngine（执行/重试/暂停）、RunController（运行管理）、Device 接口：
│              A11yDevice（无障碍）/ ShizukuDevice（adb）；UiNode/NodeQuery（控件匹配）、
│              UiDumpParser、GestureHelper、ScreenCapture（截图 + 绿色残留检测）、RunState（状态/日志）
├── shizuku/   ShizukuManager（状态/授权/绑定）、ShellService（以 shell 身份运行：执行命令、注入触摸）
├── record/    Recorder（生成脚本）、A11yRecordSource / ShizukuRecordSource（两种录制来源）、
│              RecordOverlay（录制面板）、RecordController
├── script/    Script/ScriptParser（解析、宏展开、校验）、Target（控件定位）、Expr（变量/表达式）
└── data/      Settings、AppDetector（检测已安装 App）、MediaHelper（相册读写/查找导出视频）、ScriptRepository
```

## 编译

用 Android Studio 打开本目录即可；或命令行：

```bash
./gradlew testDebugUnitTest   # 单元测试（脚本解析、宏展开、表达式）
./gradlew assembleRelease     # 输出 app/build/outputs/apk/release/app-release.apk
```

需要 JDK 17+、Android SDK 34。release 构建目前使用 debug 签名以便直接安装，正式发布请在 `app/build.gradle.kts` 中配置自己的签名。

## 权限说明

| 权限 | 用途 |
|---|---|
| 无障碍服务（无障碍模式） | 自动点击/滑动/输入、录制操作、Android 11+ 截图检测 |
| Shizuku（Shizuku 模式） | 以 adb 身份执行 uiautomator / screencap / getevent、注入触摸和按键 |
| 悬浮窗 | 进度悬浮窗（默认用无障碍悬浮窗，未授权时也可运行） |
| 照片和视频 | 把素材放进相册、查找导出的视频 |
| 通知 | 「制作完成」提醒 |
| 录屏（仅 Android 8~10） | 抠像残留绿色检测截图，拒绝后会跳过检测 |
