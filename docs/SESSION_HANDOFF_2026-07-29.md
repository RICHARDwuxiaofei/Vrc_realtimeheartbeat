# 2026-07-29 圆角 UI、三端多语言与正式发布准备续接

> 当前工作分支为 `codex/watch-sync-and-compat-handoff`，交接快照基线为
> `2f304a828f324c54d88ea247df586dd5dbb8729c`。旧 `c306` worktree 未修改。
> 用户已授权全部验收通过后备份 main、合并、上传并发布正式版；在用户完成下面两项
> 人工验收并明确确认前，不要 commit、merge、push 或发布。
>
> 用户随后明确要求先不要上传，并新增三端国际化、许可证研究和正式项目检查。
> 因此必须等待本轮人工验收、许可证署名选择和用户再次明确同意后，才能执行任何
> commit、merge、push 或 Release。

## 本轮完成

### Windows

- UI 改为与手机一致的圆角深色卡片：
  - 圆角卡片、按钮、输入框、下拉框和胶囊开关。
  - 曲线仍在首屏始终可见。
  - 源码版和稳定安装版均完成截图验收。
  - 鼠标位于曲线、表单、日志时均可滚动，已实际滚到底再返回顶部。
- 新增本机文件日志：
  - 目录：`%LOCALAPPDATA%\VrcRealtimeHeartbeat\logs`
  - 文件：`vrc-heartbeat-YYYY-MM-DD.log`
  - UTF-8 按日追加，默认保留 14 天。
  - 记录运行事件和 Tk 回调异常堆栈。
  - 界面显示当前日志路径，并提供“打开日志文件夹”按钮。
- Python `py_compile`、76 项 pytest、源码 self-test、PyInstaller 构建、打包后
  self-test、安装路径 self-test 全部通过。
- 稳定安装路径已经覆盖为圆角日志版：

```text
C:\Users\wrq18\AppData\Local\Programs\VrcRealtimeHeartbeat\VrcRealtimeHeartbeat-Python.exe
```

- 新增简体中文、English、日本語三语界面：
  - 默认读取 Windows 系统语言；中文使用简体中文、日语使用日本語，其他语言回退
    English。
  - 页面最下方“偏好设置”可以手动选择，并保存到应用设置；选择“跟随系统”可恢复自动模式。
  - 设备名称、IP、端口、BLE UUID、VRChat/OSC 参数保持原文。
- 安装版完成中文、英语、日语实屏验收；英语实屏发现并修复链路状态
  `Input engine` / `Listening on 9123` 重叠，最终安装版复验无重叠。
- 根据最终英语实屏再次重排低频控件和工具区：
  - 顶部只保留品牌与版本。
  - 曲线记录按钮移动到曲线卡右上角。
  - 启动、停止、配对二维码与高级工具分成两行等宽按钮。
  - 诊断开关加宽，英语完整显示为
    `Record chart and statistics (diagnostic mode)`。
  - 语言和版本检查移动到页面最下方“偏好设置”卡。
- 最终安装版再次用鼠标滚轮从顶部滚到底部并滚回顶部，曲线卡始终可见。
- 旧安装版备份为：

```text
C:\Users\wrq18\AppData\Local\Programs\VrcRealtimeHeartbeat\VrcRealtimeHeartbeat-Python.pre-i18n-20260729.exe
```

### Android

- Watch6 Classic `SM-R960`：`10.163.22.1:40999`
- Galaxy S24 Ultra `SM-S928B`：USB 序列号 `R5CX81QGFAV`
- 手表和手机均已安装 production/diagnostic 两包，四包均为 `1.2.0 (3)`。
- 诊断模拟链路真机通过：
  - 手表诊断版发送 62 BPM，检查时已发送 9 条。
  - 手机诊断版显示 `SIMULATED · NOT SENSOR` 并收到模拟数据。
- 发现并修复“手表无心率样本时手机没有 node ID，手机改频率无法同步”的边界问题：
  - 手表新增 `heart_rate_watch_relay` capability。
  - 手机在没有缓存 node ID 时主动查找可达手表。
- 无真实心率样本条件下完成双向同步闭环：
  - 手机选择 1 秒，手表变为 `REALTIME_1_SECOND`，两端时间戳一致。
  - 手表选择 10 秒，手机变为 10 秒，两端时间戳一致。
  - 最后两端恢复为 5 秒，时间戳一致。
- 通过 `rotaryencoder` 系统输入事件验证 production/diagnostic 均能向下滚动并返回顶部。
- 手表和手机 production/diagnostic 四包均新增简体中文、English、日本語：
  - Android app locales 支持系统自动和手动选择。
  - 四个包各自保存语言选择；最终均恢复为跟随系统。
  - 手机两版完成中/英/日首屏截图；手表两版完成中/英/日圆屏语言卡截图。
  - Kotlin UI 和动态状态字符串覆盖扫描只保留语言名称本身的 `简体中文` /
    `日本語`；设备名、地址、协议字段等技术标识保持原文。
- 最终多语言 APK 上再次启动诊断模拟器：手表显示 66 BPM、已发送 15 条，手机
  诊断版显示 `SIMULATED · NOT SENSOR` 和 64–65 BPM，确认 Watch → Phone 路径。
  本次重新安装后的 phone diagnostic 没有电脑目标地址，因此未重复本轮
  Phone → Windows；同一分支此前已完成三端模拟闭环，相关转发代码未在国际化中
  改动。
- APK 静态复核：
  - 扫码 Activity 为自定义 `PortraitCaptureActivity`。
  - `screenOrientation=portrait`。
  - `zxing_viewfinder_laser=#00000000`。
- 最新代码重新执行 Android 四变体单测、四变体 Lint、四变体 assemble：

```text
Watch diagnostic: 23
Watch production: 21
Phone diagnostic: 16
Phone production: 16
合计 76，0 failure，0 error

BUILD SUCCESSFUL in 1m 55s
214 actionable tasks: 76 executed, 138 up-to-date
```

- 四个 APK 均通过 `apksigner verify --verbose --print-certs`，签名证书
  SHA-256 为
  `ff194f4da7e0f907865be507ad652763a43bc1be377e3dc7353462c296401696`。
- 最终复核时再次运行 76 项 Python 测试、源码 self-test 和安装路径 EXE
  `--self-test`，均通过。受限沙箱中的 PyInstaller one-file TEMP 解包仍会假超时；
  在正常 Windows 进程环境运行同一安装版自检为 `PASS`，不要因此修改入口。
- 最终 `git diff --check` 通过；仅有 Git 的 LF→CRLF 提示，无空白错误。

## 最新候选产物

| 产物 | SHA-256 |
| --- | --- |
| `app-production-debug.apk` | `6a776ebb34613831bd1b5e53926ca1a0df2966734e706307126d5de38fa3e021` |
| `app-diagnostic-debug.apk` | `5c16d08d234d7a156d18ef867beeca5ee90a47e959a9f8f3f2a317b285218591` |
| `mobile-production-debug.apk` | `d8e768e5f0ff21487505acc8cc6f6fcd2b7fcab12a9baf46be7cc5cbc6d10de8` |
| `mobile-diagnostic-debug.apk` | `35d85595ae0bf7984d9d71cf45318c0034bb6b3928ad7129588484fbd061dfda` |
| `VrcRealtimeHeartbeat-Python.exe` | `25a94b118c4be18ef1789781d7356bf3728582b8ec9709eeebef88fefc7fa9cd` |

四个 APK 均为 `1.2.0 (3)`、APK Signature Scheme v2，证书 SHA-256：

```text
ff194f4da7e0f907865be507ad652763a43bc1be377e3dc7353462c296401696
```

## 许可证与 GitHub 元数据

- 仓库根目录当前没有 `LICENSE`，不能把公开仓库等同于已授予开源许可。
- 推荐 Apache License 2.0；MIT 更简单，GPL-3.0 适合要求修改版继续开源。
- 添加 LICENSE 前必须让用户确认法律署名、版权年份和许可证选择，不能从 GitHub
  用户名猜测。
- 新增 `docs/RELEASE_READINESS.md`，记录第三方许可、Android release keystore、
  Windows 代码签名、隐私、安全报告、校验和与 CI 发布门禁。
- 当前 GitHub description：

```text
Real-time Galaxy Watch heart rate bridge for Android phone, Windows, and VRChat OSC.
```

- 建议改成面向用户、同时覆盖 Galaxy Watch 与小米手环：

```text
Send live heart rate from Galaxy Watch or Xiaomi Smart Band to VRChat avatars through Android and Windows.
```

- 远端 description、topics、LICENSE 均未修改。

## 用户仍需人工验收

1. 在 Watch6 Classic 上分别打开正式版和诊断版，实际旋转物理表圈：
   - 向一个方向从顶部滚到底部。
   - 反向滚回顶部。
   - 确认方向符合直觉、步进不过快或过慢。
2. 在手机 production 和 diagnostic 中分别打开二维码扫描：
   - 画面保持竖屏。
   - 中央没有扫描竖线。
   - 相机预览比例正常，没有明显拉伸。
3. 确认最终许可证：
   - Apache-2.0 / MIT / GPL-3.0 三选一。
   - 提供 `Copyright` 使用的法律主体或个人署名及年份。

用户明确确认以上项目后，才执行：

1. 最终 `git diff --check`、版本、包名、签名、哈希复核。
2. 备份本地 main。
3. 提交当前分支、合并 main、推送 GitHub。
4. 等待 GitHub Actions 成功。
5. 创建并发布新的正式 Release。

## 2026-07-29 用户复验反馈后的收尾

- 用户确认 Watch6 Classic 的正式功能可用，但原表圈滚动有逐格跳动感。`RotaryScroll.kt`
  已改为对每次旋转位移执行 140 ms `FastOutSlowInEasing` 缓动。
- 手机正式版和诊断版页面最底部新增可见版本标识：
  - 正式版：`心率中转站 · v1.2.0 (3)`
  - 诊断版：`心率中转站（诊断） · v1.2.0 (3)`
- 覆盖 JourneyApps 的 `zxing_barcode_scanner.xml`，通过
  `zxing_viewfinder_laser_visibility=false` 从 Viewfinder 层关闭激光线动画，而不只是把颜色设为透明。
- 重新执行四变体单元测试、Lint 和 assemble：
  - Watch production：23 tests
  - Watch diagnostic：25 tests
  - Phone production：18 tests
  - Phone diagnostic：18 tests
  - 合计 84 tests，0 failure，0 error；四个 Lint 均为 0 error。
- 四个最新 APK 均已安装成功：
  - SM-R960：watch production + diagnostic
  - SM-S928B：phone production + diagnostic
  - 四包均为 `1.2.0 (3)`，production/diagnostic 包名可共存。
- 手机正式版真机滚到底部后，界面树与截图均确认版本号完整可见；诊断版界面树也确认版本号完整可见。
- 手机正式版真机扫码截图确认竖屏取景正常，中央没有横线或竖线。
- 手表正式版通过 `input rotaryencoder scroll` 自动化验证：旋转前显示顶部心率卡片，旋转后显示底部操作和语言区域，确认事件接入与页面滚动有效。
- 四个 APK 均通过 APK Signature Scheme v2 验证，证书 SHA-256 仍为
  `ff194f4da7e0f907865be507ad652763a43bc1be377e3dc7353462c296401696`。
- 验收中误触的手机“暂停发送到电脑”已恢复为发送状态；手机和手表测试 Activity 均已停止。

当前只需用户人工复验两项：

1. 物理旋转表圈的缓动手感是否满意（正式版和诊断版均已安装最新包）。
2. 如需双重确认，在手机正式版/诊断版各打开一次扫码页，确认肉眼观察不到任何扫描线动画。

在用户确认以上两项以及许可证署名信息前，仍不得 commit、merge、push 或发布。
