# 正式发布准备清单

本文记录把 VRChat 实时心率桥从可用项目整理为可长期维护、可明确授权分发的
正式项目所需事项。它不是法律意见。

## 1. 开源许可证

仓库当前没有根目录 `LICENSE`。在许可证文件加入前，公开可见不等于获得复制、
修改或再分发授权。

推荐优先考虑 **Apache License 2.0**：

- 允许商业和非商业使用、修改与再分发。
- 包含明确的专利授权与专利诉讼终止条款。
- 接受方需要保留许可证、版权声明和适用的变更说明。
- 与本项目的 Android、Python、AndroidX、ZXing、Bleak 等宽松许可证依赖通常
  更容易组合。

备选方案：

- **MIT**：文字更短、义务更少，适合希望最大限度方便复用的情况；专利条款
  不如 Apache-2.0 明确。
- **GPL-3.0**：适合希望分发修改版时也必须提供相应源代码的情况；对集成和
  再分发的约束更强。

加入许可证前必须由维护者确认：

1. 选择 Apache-2.0、MIT 或 GPL-3.0。
2. `Copyright` 中使用的法律主体名称或个人署名。
3. 版权年份范围。
4. 现有提交是否全部由同一权利人授权；如有外部贡献，需要保留贡献者的版权
   和许可信息。

不要根据 GitHub 用户名自动猜测法律署名。确定后，把官方许可证原文原样放在
仓库根目录 `LICENSE`，并在 README 与发布包中链接或包含该文件。

## 2. 第三方组件与发布包

正式发布前生成并随 Windows ZIP、Android 发布说明一起提供第三方许可清单。
至少覆盖运行时直接依赖及其传递依赖：

- AndroidX / Wear Compose / Health Services
- Google Play services Wearable
- ZXing / JourneyApps
- Kotlin / Kotlin Coroutines
- Bleak / WinRT Python bindings
- qrcode / Pillow
- PyInstaller 及其 bootloader 例外

清单应记录实际发布版本、项目网址、许可证 SPDX 标识和必须保留的声明。仅列出
依赖名称不等于履行了所有许可证义务；需要按各许可证要求附带对应文本。

## 3. 发布签名与供应链

当前四个 APK 是 debug 构建并使用同一开发调试证书。正式长期发布应：

1. 创建离线备份的 Android release keystore。
2. 只在 GitHub Actions Secrets 或受控本机环境提供密码，不提交 keystore。
3. 为 production 和 diagnostic 维持稳定、可升级的相同签名策略。
4. 对 Windows EXE 使用可信代码签名证书，降低 SmartScreen 未知发布者警告。
5. 从同一标签和同一次 CI 构建生成四个 APK、EXE、ZIP 和 `SHA256SUMS.txt`。
6. 对标签或发布产物增加签名或构建来源证明，并保留可复现的版本信息。

## 4. 隐私、安全与支持边界

发布前提供独立隐私说明，明确：

- 心率和设备诊断信息默认只在用户设备与局域网内传输。
- Windows 日志的位置、14 天保留期、可能包含的诊断字段和删除方法。
- CSV 只在用户主动记录与导出时产生。
- 应用不是医疗设备。
- 如果未来增加崩溃上报、遥测、云同步或应用商店分发，必须先更新隐私说明。

还应增加 `SECURITY.md`，给出受支持版本、漏洞私下报告渠道和响应范围；不要让
安全问题只能通过公开 Issue 报告。

## 5. GitHub 项目主页

仓库简介必须面向最终用户，避免写成开发交接说明。建议使用：

> Send live heart rate from Galaxy Watch or Xiaomi Smart Band to VRChat avatars through Android and Windows.

建议 Topics：

`vrchat`、`heart-rate`、`galaxy-watch`、`wear-os`、`android`、`osc`、
`xiaomi-smart-band`

README 首屏继续回答用户最关心的四件事：软件做什么、支持哪些设备、下载位置、
怎样开始。开发构建命令放在文档后部。

## 6. 每次正式发布门禁

- 三端简体中文、English、日本語首屏和完整滚动页面视觉检查。
- 系统语言自动选择及三种手动选择持久化检查。
- Android 四变体单元测试、Lint、assemble 和签名验证。
- Windows Python 测试、源码 self-test、打包 EXE self-test、安装路径 self-test。
- 手机 production/diagnostic 扫码预览、Galaxy Watch Classic 物理表圈、双向
  频率同步和至少一次真实或明确标注的模拟心率闭环。
- 包名、版本号、证书指纹、文件名、SHA-256 与 Release 附件逐项核对。
- `git diff --check`、干净标签、更新日志、第三方许可与发布说明复核。
- 先发布候选供人工验收，确认后再移动为正式 Release。
