# 2026-07-28 多端频率同步、双包共存与界面重构交接

> 此文档为历史快照；最新状态请以 `docs/SESSION_HANDOFF_2026-07-29.md` 为准。

> 这是当前最新交接文档。接手后必须保留工作区中的全部未提交改动；不要执行 `reset`、`checkout`、`clean` 或其他会覆盖现有文件的操作。在全部验证完成并先向用户报告之前，不要提交、推送或发布 GitHub。

## 1. 仓库与 Git 状态

- 当前续接工作区：`D:\CODE\Vrc_realtimeheartbeat`
- 当前分支：`codex/watch-sync-and-compat-handoff`
- 交接快照提交：`2f304a828f324c54d88ea247df586dd5dbb8729c`
- 旧 Codex worktree 仍停在
  `codex/watch-sync-and-compat` / `0cddb47`，其中原有未提交改动保持不动。
- 不要切回旧分支，也不要在两个工作区之间机械覆盖文件。
- 当前尚未推送分支、创建 PR 或 Release。

开始接手时先运行只读检查：

```powershell
Set-Location D:\CODE\Vrc_realtimeheartbeat
git status --short
git diff --check
git diff --stat
```

## 2. 用户当前要求

1. 手机扫码必须保持竖屏，去掉预览中央的错误扫描竖线。
2. Windows UI 不要再像旧式原生工具，要与手机端保持一致的现代深色卡片风格。
3. Windows 整页支持鼠标滚轮上下滚动，曲线功能必须显眼，不能隐藏到不易发现的诊断选项中。
4. Galaxy Watch Classic 支持旋转物理表圈滚动；正式版和诊断版都要适配。
5. 手表和手机均可选择 `1 / 5 / 10 秒`发送频率；任意一端修改后自动同步到另一端。
6. 手表正式版和诊断版能够同时安装。
7. 支持 Galaxy Watch4 系列及更新的 Wear OS 三星手表。
8. 修复后重新运行全部测试、Lint、APK/EXE 构建和三端真机检查。
9. 全部检查完成后先报告；未经用户再次授权不要提交、推送或发布。

## 3. 已实现但尚未提交的功能

### 3.1 手机竖屏扫码

新增：

```text
mobile/src/main/java/best/nagikokoro/watch6heartrateprobe/mobile/PortraitCaptureActivity.kt
mobile/src/main/res/values/zxing_colors.xml
```

行为：

- ZXing 使用自定义 `PortraitCaptureActivity`。
- Manifest 将该 Activity 固定为 portrait。
- `zxing_viewfinder_laser` 覆盖为透明，去掉中央错误竖线。
- 构建产物曾通过 `aapt` 检查：自定义 Activity 存在、方向为 portrait、扫描激光颜色透明。

手机当前未连接 ADB，因此仍缺少真实手机上的最终画面验收。

### 3.2 手机与手表发送频率双向同步

统一选项：

- `1 秒`
- `5 秒`
- `10 秒`

主要实现：

- `WatchRelaySettings` 保存频率和更新时间戳。
- 新增 `WatchRelayFrequencySync.kt`，手表选择后通过 Wear Data Layer 控制消息同步到手机。
- 手机保存频率和更新时间戳，并把手机端选择同步回手表。
- 同步时比较更新时间戳，避免旧消息覆盖新选择。
- 手机收到手表样本和 ACK 时也会校准频率状态。
- 手表前台服务运行时可以动态切换：
  - 1 秒档切到实时/直接传感器策略。
  - 5/10 秒档切回 Health Services 批量策略。
  - 切换不需要结束当前测量会话。
- 修正控制消息解析：仅同步频率时不会意外关闭诊断模式。

已在 Watch6 正式版真机上验证运行中选择 1 秒和 5 秒均能写入设置，前台服务保持运行；最后恢复为 5 秒。由于手机未连接，完整双向闭环仍未验证。

### 3.3 正式版与诊断版共存

手表包名：

```text
正式版：best.nagikokoro.watch6heartrateprobe
诊断版：best.nagikokoro.watch6heartrateprobe.diagnostic
```

手机也增加 matching product flavors：

```text
正式版：best.nagikokoro.watch6heartrateprobe
诊断版：best.nagikokoro.watch6heartrateprobe.diagnostic
```

必须保持两组配对：

- `watch-production` + `phone-production`
- `watch-diagnostic` + `phone-diagnostic`

Wear Data Layer 要求手机与手表应用包名、签名匹配，因此不能只给手表诊断版增加后缀而让它继续连接正式手机包。

Galaxy Watch6 曾成功同时安装两个 `1.2.0 (3)` 包，并确认正式版为前台应用。

### 3.4 Windows UI 重构

最新改动位于：

```text
pc-python/vrc_heartbeat/app.py
```

已经完成的代码改动：

- 配色与手机端一致：
  - 页面背景 `#0B0B0F`
  - 卡片 `#121216`
  - 抬升卡片 `#1B1B20`
  - 紫色主操作 `#D0BCFF`
  - 珊瑚色心率强调 `#FFB4AB`
  - 成功色 `#8BD5A3`
- 窗口默认 `1120 × 820`，最小 `900 × 640`。
- 心率主卡、区间统计、链路状态、曲线、来源、连接工具和运行记录重新分区。
- 曲线面板始终显示，不再在关闭诊断模式时 `pack_forget()`。
- 顶部操作改为“开始曲线记录 / 停止曲线记录”。
- 未记录时曲线卡明确提示“点击右上角开始记录”。
- 页面内容放进 `Canvas + Scrollbar`。
- 绑定 Windows `<MouseWheel>` 和兼容的 `<Button-4>/<Button-5>`，滚轮控制整页上下滑动。

已完成：

- `py_compile` 通过。
- Python 71 项测试通过。
- Tk 构造冒烟测试成功，输出：

```text
VRChat 心率桥 · Python 1120 820
```

尚未完成：

- 新 UI 的截图级视觉验收。
- 实际从页面顶部用鼠标滚轮滚到底部再滚回顶部。
- 根据真实截图检查文字截断、控件拥挤、深色 Entry/Combobox 和按钮状态。
- 新 UI 重新打包 EXE、安装到稳定路径和更新桌面正在运行的程序。

最后一次后台启动源码 UI 的命令被中断。2026-07-28 交接检查时发现：

```text
PID 16740
进程 pythonw
路径 D:\CODE\Vrc_realtimeheartbeat\pc-python\.venv\Scripts\pythonw.exe
```

接手后先重新检查该进程是否仍存在。只可停止路径和命令明确属于本项目 UI 预览的进程，不要误杀用户其他 Python 程序。

### 3.5 Watch Classic 旋转表圈

新增：

```text
app/src/main/java/best/nagikokoro/watch6heartrateprobe/RotaryScroll.kt
```

实现使用：

- `onRotaryScrollEvent`
- `FocusRequester`
- `focusable`
- `ScrollState.scrollBy`

已接入：

```text
app/src/production/java/.../ProductionMainActivity.kt
app/src/diagnostic/java/.../MainActivity.kt
```

两个页面均在原有 `verticalScroll(pageScroll)` 后添加：

```kotlin
.rotaryBezelScroll(pageScroll)
```

第一次编译因缺少 `androidx.compose.foundation.gestures.scrollBy` 导入失败；导入已补上。之后以下任务成功：

```text
:app:compileProductionDebugKotlin
:app:compileDiagnosticDebugKotlin
BUILD SUCCESSFUL
```

目前只确认编译通过，尚未重新 assemble、安装新 APK，也没有在 Watch6 Classic 上实际旋转表圈验收。

Android 官方 API 说明旋转侧键或表圈事件只有在组件或子组件获得焦点时才会送达，因此这里显式请求焦点并消费事件：

- <https://developer.android.com/reference/kotlin/androidx/compose/ui/input/rotary/onRotaryScrollEvent.modifier>

### 3.6 Windows 曲线入口

重构前曾在窗口顶部增加“显示曲线与统计”按钮并实际展开验证过，但用户仍认为整体界面过时。

最新重构改变了策略：曲线卡始终可见，按钮只负责开始或停止采集、CSV 和统计，不再负责把整张曲线卡隐藏/显示。

## 4. 版本与兼容范围

当前开发版本：

```text
Android versionName: 1.2.0
Android versionCode: 3
Python: 1.2.0
```

目标三星手表：

- Galaxy Watch4 系列及更新的 Wear OS Galaxy Watch。
- `minSdk 30`，对应 Wear OS 3 / Android 11 起。
- Galaxy Watch4 的三星官方更新页面在不同地区已显示 AndroidWear/Wear OS 6 更新。
- Watch4 之前使用 Tizen 的 Galaxy Watch、Watch Active、Watch3 等不支持。
- 其他厂商 Wear OS 设备理论上可以安装，但后台心率行为尚未逐机验证。

官方参考：

- <https://doc.samsungmobile.com/SM-R860/020032210827/roh.html>
- <https://www.samsung.com/us/support/answer/ANS10003456/>
- <https://developer.android.com/docs/quality-guidelines/wear-app-quality>

## 5. 已执行的测试与构建

### 5.1 最新 Windows UI 改动后

```text
Python py_compile：通过
Python pytest：71 passed
Tk UI 构造冒烟：通过，1120 × 820
Watch production Kotlin compile：通过
Watch diagnostic Kotlin compile：通过
```

### 5.2 UI/表圈最新改动之后的完整 Android 门禁

2026-07-28 在交接快照 `2f304a8` 上重新完整成功执行：

```text
:app:testDiagnosticDebugUnitTest
:app:testProductionDebugUnitTest
:mobile:testDiagnosticDebugUnitTest
:mobile:testProductionDebugUnitTest
:app:lintDiagnosticDebug
:app:lintProductionDebug
:mobile:lintDiagnosticDebug
:mobile:lintProductionDebug
:app:assembleDiagnosticDebug
:app:assembleProductionDebug
:mobile:assembleDiagnosticDebug
:mobile:assembleProductionDebug
```

测试统计：

```text
Watch diagnostic：23
Watch production：21
Phone diagnostic：16
Phone production：16
合计：76，0 failure，0 error
```

Gradle 最终输出：

```text
BUILD SUCCESSFUL in 1m 5s
214 actionable tasks: 6 executed, 208 up-to-date
```

仅有已安装 Android SDK command-line tools 与 SDK XML 版本的兼容警告，不影响任务结果。

### 5.3 最新 Windows UI 与 EXE 门禁

2026-07-28 在正常 Windows 进程环境成功执行：

```text
Python pytest：71 passed
源码 --self-test：退出码 0
PyInstaller 单文件 EXE：构建成功
打包后 --self-test：15 秒内退出码 0
安装路径 EXE --self-test：退出码 0
```

最新产物：

```text
SHA-256: 47367106a87da1bf4137e164c5f069327692faff87d72e21b0b283cf18e81b31
```

已完成源码 UI 与安装版 UI 的截图级验收。曲线卡在首屏始终可见；鼠标位于曲线、
来源表单和运行日志区域时均能滚动，已实际从顶部滚到底部再滚回顶部。

## 6. PyInstaller `--self-test` 沙箱注意事项

PyInstaller one-file 在 Codex 受限沙箱中可能只能创建空 `_MEI...` 根目录，随后无法创建解包子目录。`--windowed` 会隐藏：

```text
Failed to create parent directory structure.
```

表面现象是无标题隐藏窗口或 `--self-test` 超时。这不是应用业务线程死锁。

正确流程：

1. 先确认没有旧的项目 EXE 或诊断进程锁住输出文件。
2. 经批准在正常 Windows 进程环境运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\pc-python\Build-Exe.ps1 `
  -Python D:\CODE\Vrc_realtimeheartbeat\pc-python\.venv\Scripts\python.exe
```

3. 要求构建脚本内部 15 秒有界自检成功。
4. 复制到稳定安装路径后再次执行 `--self-test`。

不要为沙箱解包限制修改或删除正常的 self-test 入口。

## 7. 设备与安装状态

### 7.1 Galaxy Watch6

设备：

```text
型号：SM-R960 Galaxy Watch6 Classic
最近使用的是手表无线 ADB 临时地址，端口会随无线调试重启而变化。
系统：Android 16 / API 36
Build：R960XXU2CZB6
```

之前连接正常，并已安装：

```text
best.nagikokoro.watch6heartrateprobe
best.nagikokoro.watch6heartrateprobe.diagnostic
```

两者当时均为 `1.2.0 (3)`。

2026-07-28 最新续接检查时 ADB 状态为：

```text
adb devices -l：空
adb mdns services：空
连接原无线 ADB 临时地址：超时 10060
```

无线调试端口可能已经变化或连接需要重新授权。重新安装表圈版本前必须先恢复 ADB。

### 7.2 Android 手机

- 之前设备为 Galaxy S24 Ultra，型号 SM-S928B。
- 当前没有出现在 `adb devices`。
- 因此尚未安装本轮 `1.2.0` 手机 production/diagnostic APK。
- 竖屏扫码和频率双向同步仍缺最终手机真机验收。

### 7.3 Windows

桌面快捷方式：

```text
%USERPROFILE%\Desktop\VRChat 心率桥.lnk
```

目标：

```text
%LOCALAPPDATA%\Programs\VrcRealtimeHeartbeat\VrcRealtimeHeartbeat-Python.exe
```

稳定目录已覆盖为 UI 重构后的 `1.2.0` 构建，安装路径 `--self-test` 退出码为 0。
桌面快捷方式目标已复核无误。旧 EXE 保存在同目录：

```text
%LOCALAPPDATA%\Programs\VrcRealtimeHeartbeat\VrcRealtimeHeartbeat-Python.pre-ui-20260728.exe
```

## 8. 当前构建产物

2026-07-28 重新执行完整门禁后的产物：

| 产物 | SHA-256 | 说明 |
| --- | --- | --- |
| `app-production-debug.apk` | `9c1d6aace86d8f336ea1ed64ae955f43b3ff40f78a5eeaa3a58ef9e2b479f6e9` | 已含旋转表圈改动，尚未手表安装验收 |
| `app-diagnostic-debug.apk` | `c5824829cd8256da07be7190b90dbdc9990a2c6ce331ece7242b4b937e649364` | 已含旋转表圈改动，尚未手表安装验收 |
| `mobile-production-debug.apk` | `e0f793c9b67edd5a20553d0d7674b8532efe7de06b16eee2ed1801e0ed310a8b` | 尚未手机安装验收 |
| `mobile-diagnostic-debug.apk` | `1134b3e60c358ad9aa3a8cfc1d088851d1b0d8d1946cedfecf56509810d93b30` | 尚未手机安装验收 |
| `VrcRealtimeHeartbeat-Python.exe` | `47367106a87da1bf4137e164c5f069327692faff87d72e21b0b283cf18e81b31` | 已安装并通过自检、截图与滚轮验收 |

四个 APK 均为 `versionName 1.2.0` / `versionCode 3`。正式版包名为
`best.nagikokoro.watch6heartrateprobe`，诊断版包名带 `.diagnostic` 后缀。
四包均通过 APK Signature Scheme v2 验证，调试证书 SHA-256 为：

```text
ff194f4da7e0f907865be507ad652763a43bc1be377e3dc7353462c296401696
```

## 9. 接手后的准确执行顺序

1. 检查 `git status`、`git diff --check` 和项目相关残留进程，保留全部未提交改动。
2. 启动重构后的 Windows 源码 UI。
3. 截图检查首屏布局、文字截断、卡片间距、按钮状态和深色控件。
4. 用鼠标滚轮从顶部滚到底部，再滚回顶部；确认鼠标位于曲线、表单和日志区域时页面均能滚动。
5. 根据实际画面继续调整，不要只做代码级验收。
6. 重新运行 Python 71 项测试和源码 self-test。
7. 在正常 Windows 环境重新运行 `Build-Exe.ps1`，要求打包后 self-test 成功。
8. 用最新 EXE 覆盖稳定安装目录，复测 self-test，检查桌面快捷方式并打开新版。
9. 重新执行 Android 四变体单测、Lint 和 assemble。
10. 恢复 Watch6 ADB，安装最新 production/diagnostic APK，确认两个包共存。
11. 打开正式版，实际转动 Classic 表圈验证页面上下滚动；再验证诊断版。
12. 手机连上后安装配套 production/diagnostic APK。
13. 手机真机验证扫码为竖屏且中央无竖线。
14. 验证手机改频率后手表自动更新，手表改频率后手机自动更新。
15. 再执行 `git diff --check`、版本号、包名、签名、APK/EXE 哈希和安装状态最终检查。
16. 更新 README、CHANGELOG 和最终日志。
17. 先向用户报告；未经明确允许不要 commit、push 或发布。

## 10. 当前结论

代码实现已覆盖用户提出的主要功能，但不能宣称完成：

- Windows 新 UI 已完成源码与安装版视觉、滚轮、EXE 构建和自检。
- Android 四变体单测、Lint、assemble 已在最新代码上通过。
- Watch 表圈支持已进入最新 APK，但设备未连接，尚未安装和实际旋转验收。
- 手机未连接，扫码和频率双向同步尚未真机闭环。

因此当前阻塞点只有三端真机中的手表/手机两端。恢复 Watch6 和手机 ADB 后仍需完成：

1. 手表 production/diagnostic 双包安装、共存和 Classic 表圈滚动。
2. 手机 production/diagnostic 双包安装、竖屏扫码与中央无竖线。
3. 手机与手表 `1 / 5 / 10 秒`频率双向同步闭环。

在这些真机检查完成并先向用户报告前，不要备份/合并 main、commit、push 或发布。
