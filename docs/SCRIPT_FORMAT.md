# 步骤脚本格式（JSON）

一键木偶打光的全部自动化步骤都写在 JSON 脚本里。内置示例：
[`app/src/main/assets/scripts/default_flow.json`](../app/src/main/assets/scripts/default_flow.json)。

NV MAX / Motion Ninja 更新后如果某一步失败，可以：

1. 在 App 的「脚本 / 录制 → 内置默认流程 → 查看/编辑」里修改，保存为新脚本并「设为当前脚本」；
2. 或者用「录制模式」手动操作一遍，自动生成脚本；
3. 或者在电脑上改好 JSON，用「导入 JSON」导入。

## 顶层结构

```jsonc
{
  "name": "脚本名称",
  "version": 1,
  "screen": { "width": 1080, "height": 2400 },   // 参考分辨率：坐标 > 1 时按它换算
  "defaults": {
    "waitMs": 1200,          // 每步执行后的默认等待
    "retries": 3,            // 失败自动重试次数
    "findTimeoutMs": 2500,   // 查找控件的最长时间，超时后使用坐标兜底
    "verifyTimeoutMs": 6000  // verify 校验的最长等待
  },
  "variables": { "keyTolerance": "0.30" },   // 脚本变量，可用 ${名字} 引用
  "macros":    { "addEffect": [ ...步骤... ] },  // 宏：可复用的一组步骤
  "steps":     [ ...步骤... ]
}
```

## 运行时变量

除了 `variables` 里定义的，App 还会注入：

| 变量 | 含义 |
|---|---|
| `${nvmaxPackage}` / `${motionNinjaPackage}` | 自动检测到的包名 |
| `${lightColor}` / `${lightColorHex}` | 光源颜色，`#FFA040` / `FFA040` |
| `${keyframeTime}` | 关键帧时间点（秒，默认 1.5） |
| `${shakeStrength}` | 摇晃强度（0~1，默认 0.3） |
| `${saturationEnabled}` | 是否启用饱和度渐变（true/false） |
| `${greenIsVideo}` | 绿幕素材是否为视频 |
| `${screenWidth}` / `${screenHeight}` | 屏幕像素 |
| `${nvExportUri}` / `${finalVideo}` | `WAIT_NEW_MEDIA` 用 `saveAs` 保存的结果 |

数值字段都可以写表达式：`"${keyframeTime} * 0.12"`、`"min(${keyTolerance} + 0.1, 1)"`，支持 `+ - * / ()`、`min max abs round`。

## 通用步骤字段

| 字段 | 说明 |
|---|---|
| `id` | 步骤编号（出错时显示），可省略 |
| `phase` | 阶段名，写一次后后续步骤沿用，悬浮窗中显示 |
| `action` | 动作类型，见下表 |
| `desc` | 悬浮窗中显示的说明 |
| `waitMs` | 执行后等待（毫秒） |
| `retries` | 覆盖默认重试次数 |
| `optional` | `true` 时重试用尽后直接跳过，不暂停 |
| `enabledIf` | 条件，如 `"${saturationEnabled}"`、`"!${greenIsVideo}"`，为假时跳过本步 |
| `verify` | 执行后必须出现的控件（Target），用于校验成功 |
| `verifyGone` | 执行后必须消失的控件 |
| `delayBeforeMs` | 执行前等待 |

## Target：控件定位

```jsonc
{
  "text": ["复制", "Duplicate"],   // 控件文字（任意一个匹配即可；无 desc 条件时也会匹配 contentDescription）
  "desc": ["复制"],                // contentDescription
  "viewId": "btn_copy",            // 资源 id（可只写 id/ 后面的部分）
  "className": "EditText",         // 类名结尾匹配
  "exact": false,                  // true = 完全相等，false = 包含
  "index": 0,                      // 多个匹配时取第几个，-1 = 最后一个
  "sort": "y",                     // 排序：y = 从上到下，x = 从左到右
  "scroll": false,                 // 找不到时滚动列表继续找
  "gesture": false,                // 强制用手势点击控件中心
  "x": 0.5, "y": 0.9               // 找不到控件时的坐标兜底（0~1 为屏幕比例）
}
```

查找顺序：文字 / 描述 / viewId → 坐标（`dispatchGesture`）。点击时优先对控件执行 `ACTION_CLICK`，失败再用手势点控件中心。

## 动作类型

| action | 关键字段 | 说明 |
|---|---|---|
| `LAUNCH_APP` | `package`, `freshStart` | 打开 App，等待其出现在前台 |
| `CLICK` / `LONG_CLICK` | `target` | 点击 / 长按 |
| `SET_TEXT` | `target`(可选), `text`, `imeEnter` | `ACTION_SET_TEXT` 输入；没有 target 时用当前焦点输入框 |
| `SET_VALUE` | `label`, `value`, `min`, `max`, `modes`, `slider`, `dialogTap` | 设置参数值，按 `modes` 依次尝试：`field` 标签旁的输入框、`range` 标签旁的进度条（`ACTION_SET_PROGRESS`）、`dialog` 点开数值弹窗输入、`slider` 手势拖滑块（`slider: {x1, x2, y 或 dy, from: tap/start/end}`） |
| `SWIPE` | `from`, `to`, `durationMs` | 快速滑动 |
| `DRAG` | `from`, `to`, `holdMs`, `durationMs`, `holdEndMs` | 按住 → 拖动 → 停留后松手（拖图层、拖滑块）。`from`/`to` 是 Target，可加 `dx`/`dy` 偏移 |
| `PINCH` | `center`, `scale`, `span`, `durationMs` | 双指缩放 |
| `TIMELINE_SEEK` | `seconds`, `area{y,x1,x2}`, `pxPerSecond`, `reset`, `mode` | 拖动时间轴到指定秒数（`pxPerSecond` 为每秒对应屏幕宽度的比例；`reset` 先拖回开头）；`mode: "tap"` + `originX` 用于点击刻度尺定位 |
| `WAIT` | `ms` | 固定等待 |
| `WAIT_FOR` | `target`, `timeoutMs` | 等待控件出现 |
| `BACK` / `HOME` | | 系统返回 / 桌面 |
| `IME_ENTER` | | 输入法回车（Android 11+） |
| `PREPARE_MEDIA` | | 把背景图、绿幕素材复制到相册「一键木偶打光」（绿幕 = 最新第 1 项，背景 = 第 2 项，文件名分别以 `木偶角色_`、`木偶背景_` 开头） |
| `WAIT_NEW_MEDIA` | `timeoutMs`, `stableMs`, `saveAs`, `ensureInGallery` | 在相册 + 下载目录里等待上一步导出的最新视频，文件大小稳定后视为导出完成 |
| `GREEN_CHECK` | `region`, `threshold`, `maxAttempts`, `fixSteps`, `settleMs` | 截图统计区域内绿幕绿占比，超过阈值就执行 `fixSteps`（例如调高容差）后重新检测 |
| `SET_VAR` | `name`, `value` | 修改变量（可用表达式） |
| `NOTIFY` | `title`, `text`, `openUri` | 发送通知，点击打开视频 |
| `MACRO` | `name`, `args` | 调用宏 |

## 宏

```jsonc
"macros": {
  "addEffect": [
    { "action": "CLICK", "desc": "打开添加效果", "target": { "text": ["添加效果"], "x": 0.5, "y": 0.94 } },
    { "action": "SET_TEXT", "desc": "输入「$[keyword]」", "text": "$[keyword]" },
    { "action": "CLICK", "desc": "选择「$[item]」", "target": { "text": "$[item]", "exact": true } }
  ]
},
"steps": [
  { "action": "MACRO", "name": "addEffect", "desc": "搜索灰光",
    "args": { "keyword": "光", "item": ["灰光", "Gray Light"] } }
]
```

`$[参数]` 在解析时替换：字段值恰好是 `"$[item]"` 时整体替换（可以是数组/数字），在字符串中间时替换为文字（数组取第一个）。
调用处的 `optional`、`enabledIf` 会传给宏内每一步。修改一次宏，所有调用处同时生效——App 改版时通常只需改宏。

## 出错时

每步失败会自动重试（默认 3 次）。仍失败时流程暂停，悬浮窗显示「第 N 步「说明」失败：原因」，可以：

- 手动把界面调整好后点「重试」
- 「跳过此步」继续后面的流程
- 「停止」

运行日志在「脚本 / 录制 → 运行日志」中，可分享给他人排查。
