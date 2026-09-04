from __future__ import annotations

import ctypes
import locale
from typing import Final


SYSTEM: Final = "system"
CHINESE: Final = "zh"
ENGLISH: Final = "en"
JAPANESE: Final = "ja"
LANGUAGES: Final = (SYSTEM, CHINESE, ENGLISH, JAPANESE)


def normalize_language(value: object) -> str:
    return value if isinstance(value, str) and value in LANGUAGES else SYSTEM


def system_language() -> str:
    tag = ""
    if hasattr(ctypes, "windll"):
        try:
            buffer = ctypes.create_unicode_buffer(85)
            if ctypes.windll.kernel32.GetUserDefaultLocaleName(buffer, len(buffer)):
                tag = buffer.value
        except (AttributeError, OSError):
            pass
    if not tag:
        try:
            tag = locale.getlocale()[0] or ""
        except (TypeError, ValueError):
            tag = ""
    lowered = tag.replace("_", "-").lower()
    if lowered.startswith("zh"):
        return CHINESE
    if lowered.startswith("ja"):
        return JAPANESE
    return ENGLISH


class Translator:
    def __init__(self, language: str = SYSTEM) -> None:
        self.selected_language = normalize_language(language)
        self.language = system_language() if self.selected_language == SYSTEM else self.selected_language

    def text(self, source: object) -> str:
        value = str(source)
        if self.language == CHINESE:
            return value
        translation = _EXACT.get(value)
        if translation is not None:
            return translation[1] if self.language == JAPANESE else translation[0]
        translated = value
        for key, pair in sorted(
            {**_EXACT, **_PHRASES}.items(),
            key=lambda item: len(item[0]),
            reverse=True,
        ):
            if key in translated:
                translated = translated.replace(key, pair[1] if self.language == JAPANESE else pair[0])
        return translated

    def language_options(self) -> dict[str, str]:
        return {
            SYSTEM: self.text("跟随系统"),
            CHINESE: self.text("简体中文"),
            ENGLISH: "English",
            JAPANESE: "日本語",
        }


_EXACT: Final[dict[str, tuple[str, str]]] = {
    "跟随系统": ("Follow system", "システムに従う"),
    "简体中文": ("简体中文", "簡体字中国語"),
    "语言": ("Language", "言語"),
    "VRChat 心率桥": ("VRChat Heart Rate Bridge", "VRChat 心拍数ブリッジ"),
    "VRChat 心率桥 · Python": ("VRChat Heart Rate Bridge · Python", "VRChat 心拍数ブリッジ · Python"),
    "实时心率": ("Live heart rate", "リアルタイム心拍数"),
    "区间统计": ("Range statistics", "範囲統計"),
    "最低": ("Minimum", "最小"),
    "最高": ("Maximum", "最大"),
    "平均": ("Average", "平均"),
    "链路状态": ("Connection status", "接続状態"),
    "输入引擎": ("Input engine", "入力エンジン"),
    "输入设备": ("Input device", "入力デバイス"),
    "显示范围": ("Display range", "表示範囲"),
    "心率来源": ("Heart rate source", "心拍数ソース"),
    "选择 Galaxy Watch 手机中转，或让小米手环直接连接这台电脑": (
        "Use the phone relay for Galaxy Watch, or connect a Xiaomi Smart Band directly to this PC",
        "Galaxy Watchはスマートフォン経由、Xiaomi Smart BandはこのPCへ直接接続できます",
    ),
    "手机中转（Galaxy / 小米）": ("Phone relay (Galaxy / Xiaomi)", "スマホ中継（Galaxy / Xiaomi）"),
    "电脑直连小米手环（实验）": ("Direct Xiaomi Band BLE (test)", "Xiaomi Band直接BLE（試験）"),
    "扫描": ("Scan", "スキャン"),
    "连接": ("Connect", "接続"),
    "断开 BLE": ("Disconnect BLE", "BLEを切断"),
    "连接与工具": ("Connection and tools", "接続とツール"),
    "高级工具": ("Advanced tools", "高度なツール"),
    "偏好设置": ("Preferences", "設定"),
    "版本与更新": ("Version and updates", "バージョンと更新"),
    "手机 UDP 端口": ("Phone UDP port", "スマートフォンUDPポート"),
    "VRChat OSC 端口": ("VRChat OSC port", "VRChat OSCポート"),
    "发送到 VRChat OSC": ("Send to VRChat OSC", "VRChat OSCへ送信"),
    "发送到 OyasumiVR": ("Send to OyasumiVR", "OyasumiVRへ送信"),
    "正在自动发现": ("Auto-discovering", "自動検出中"),
    "已通过 OSCQuery 发现 OyasumiVR：": (
        "Discovered OyasumiVR through OSCQuery: ",
        "OSCQueryでOyasumiVRを検出：",
    ),
    "OyasumiVR OSCQuery 服务已离线，继续自动发现": (
        "OyasumiVR OSCQuery went offline; continuing discovery",
        "OyasumiVR OSCQueryがオフラインになりました。検出を継続します",
    ),
    "记录曲线与统计（诊断模式）": ("Record chart and statistics (diagnostic mode)", "グラフと統計を記録（診断モード）"),
    "启动接收": ("Start receiver", "受信を開始"),
    "停止": ("Stop", "停止"),
    "Avatar 参数测试": ("Avatar parameter test", "Avatarテスト"),
    "显示配对二维码": ("Show pairing QR code", "ペアリングQRコードを表示"),
    "一键诊断": ("Run diagnostics", "診断"),
    "导出 CSV…": ("Export CSV…", "CSV出力…"),
    "运行记录": ("Activity log", "実行ログ"),
    "打开日志文件夹": ("Open log folder", "ログフォルダー"),
    "开始曲线记录": ("Start chart recording", "グラフ記録を開始"),
    "停止曲线记录": ("Stop chart recording", "グラフ記録を停止"),
    "等待手机数据": ("Waiting for phone data", "スマートフォンデータを待機中"),
    "等待小米手环 BLE": ("Waiting for Xiaomi Smart Band BLE", "Xiaomi Smart Band BLEを待機中"),
    "尚未收到数据包": ("No data received yet", "データをまだ受信していません"),
    "尚未收到当前来源数据": ("No data received from the selected source", "選択したソースからデータをまだ受信していません"),
    "未启动": ("Not started", "未開始"),
    "已开启": ("On", "オン"),
    "已关闭": ("Off", "オフ"),
    "点击右上角开始记录": ("Use the top-right button to start recording", "右上のボタンで記録を開始"),
    "正在检查 GitHub…": ("Checking GitHub…", "GitHubを確認中…"),
    "正在扫描标准心率设备…": ("Scanning standard Heart Rate devices…", "標準心拍数デバイスをスキャン中…"),
    "直连模式未启用": ("Direct BLE mode is off", "直接BLEモードは無効"),
    "已保存设备": ("Saved device", "保存済みデバイス"),
    "直连模式未启用；继续使用手机 UDP 中转": (
        "Direct BLE mode is off; phone UDP relay remains active",
        "直接BLEモードは無効。スマートフォンUDP中継を継続します",
    ),
    "已断开；可重新扫描或连接": ("Disconnected; scan or connect again", "切断済み。再スキャンまたは再接続できます"),
    "扫描小米手环": ("Scan Xiaomi Smart Band", "Xiaomi Smart Bandをスキャン"),
    "连接小米手环": ("Connect Xiaomi Smart Band", "Xiaomi Smart Bandへ接続"),
    "请先启动接收器。": ("Start the receiver first.", "先に受信を開始してください。"),
    "请先扫描并选择一个心率设备。": ("Scan and select a heart rate device first.", "先に心拍数デバイスをスキャンして選択してください。"),
    "BLE 已连接，等待心率": ("BLE connected; waiting for heart rate", "BLE接続済み。心拍数を待機中"),
    "BLE 数据警告": ("BLE data warning", "BLEデータ警告"),
    "BLE 状态已更新": ("BLE status updated", "BLE状態を更新しました"),
    "BLE 直连引擎": ("Direct BLE engine", "直接BLEエンジン"),
    "BLE 心率设备": ("BLE heart rate device", "BLE心拍数デバイス"),
    "BLE 已断开，5 秒后重连…": ("BLE disconnected; reconnecting in 5 seconds…", "BLE切断。5秒後に再接続します…"),
    "Windows BLE 线程启动超时": ("Windows BLE thread start timed out", "Windows BLEスレッドの開始がタイムアウトしました"),
    "电脑 BLE 直连目前只支持 Windows": ("Direct PC BLE is currently supported only on Windows", "PC直接BLEは現在Windowsのみ対応しています"),
    "缺少 BLE 组件，请安装最新版电脑端": ("BLE component is missing; install the latest Windows app", "BLEコンポーネントがありません。最新版Windowsアプリをインストールしてください"),
    "未找到心率广播；请在手环开启“共享心率”": ("No heart rate broadcast found; enable “Share heart rate” on the band", "心拍数ブロードキャストが見つかりません。バンドで「心拍数の共有」を有効にしてください"),
    "设备不在本次扫描结果中，请重新扫描": ("The device is not in the current scan results; scan again", "現在のスキャン結果にデバイスがありません。再スキャンしてください"),
    "设备没有标准心率特征 0x2A37": ("The device does not expose standard Heart Rate characteristic 0x2A37", "デバイスに標準心拍数特性0x2A37がありません"),
    "忽略了一条无效的标准心率数据": ("Ignored one invalid standard heart rate packet", "無効な標準心拍数データを1件無視しました"),
    "BLE 连接异常": ("BLE connection error", "BLE接続エラー"),
    "Windows BLE 组件无法启动": ("Windows BLE component could not start", "Windows BLEコンポーネントを開始できません"),
    "通常每个套接字地址(协议/网络地址/端口)只允许使用一次。": (
        "This UDP port is already in use by another process.",
        "このUDPポートは別のプロセスが使用中です。",
    ),
    "启动失败": ("Start failed", "開始失敗"),
    "无法启动接收器": ("Unable to start receiver", "受信を開始できません"),
    "已停止": ("Stopped", "停止済み"),
    "仅 Windows 支持直接打开日志文件夹": ("Opening the log folder is supported only on Windows", "ログフォルダーを直接開けるのはWindowsのみです"),
    "接收器已停止": ("Receiver stopped", "受信を停止しました"),
    "请先开启“发送到 VRChat OSC”。": ("Enable “Send to VRChat OSC” first.", "先に「VRChat OSCへ送信」を有効にしてください。"),
    "Avatar 测试已发送": ("Avatar test sent", "Avatarテストを送信しました"),
    "配对二维码": ("Pairing QR code", "ペアリングQRコード"),
    "没有找到可用的本机局域网 IPv4。": ("No usable local LAN IPv4 address was found.", "利用可能なローカルLAN IPv4アドレスが見つかりません。"),
    "二维码组件未安装，请重新安装或使用最新版 EXE。": (
        "The QR component is missing. Reinstall or use the latest EXE.",
        "QRコンポーネントがありません。再インストールするか最新版EXEを使用してください。",
    ),
    "手机扫码配对": ("Pair with phone", "スマートフォンでペアリング"),
    "在手机端点击“扫码配对”，识别后会自动保存电脑地址。": (
        "On the phone, tap “Scan to pair PC”. The PC address is saved automatically.",
        "スマートフォンで「QRコードでPCとペアリング」をタップすると、PCアドレスが自動保存されます。",
    ),
    "请先开启诊断模式。": ("Enable diagnostic mode first.", "先に診断モードを有効にしてください。"),
    "输入模式": ("Input mode", "入力モード"),
    "电脑直连小米手环 BLE": ("Direct Xiaomi Smart Band BLE", "Xiaomi Smart Band直接BLE"),
    "BLE 引擎": ("BLE engine", "BLEエンジン"),
    "小米手环": ("Xiaomi Smart Band", "Xiaomi Smart Band"),
    "真实心率": ("Real heart rate", "実測心拍数"),
    "本机 IPv4": ("Local IPv4", "ローカルIPv4"),
    "UDP 接收器": ("UDP receiver", "UDPレシーバー"),
    "手机数据": ("Phone data", "スマートフォンデータ"),
    "小米手环 → 电脑 BLE": ("Xiaomi Smart Band → PC BLE", "Xiaomi Smart Band → PC BLE"),
    "手表模拟心率（非传感器）": ("Watch simulated heart rate (not sensor data)", "Watch模擬心拍数（センサーデータではありません）"),
    "电脑端链路状态正常。": ("The PC relay path is healthy.", "PC転送経路は正常です。"),
    "接收器正常；未通过项可能只是当前来源尚未发送或 OSC 被关闭。": (
        "The receiver is healthy. Unconfirmed items may simply have no source data yet or OSC may be off.",
        "受信は正常です。未確認項目は、まだソースデータがないかOSCが無効な可能性があります。",
    ),
    "请先启动接收器，再连接当前选择的心率来源。": (
        "Start the receiver, then connect the selected heart rate source.",
        "受信を開始してから、選択した心拍数ソースへ接続してください。",
    ),
    "诊断模式": ("Diagnostic mode", "診断モード"),
    "诊断模式已开启：扩展字段、CSV、曲线和统计开始工作": (
        "Diagnostic mode enabled: extended fields, CSV, chart, and statistics are active",
        "診断モード有効：拡張項目、CSV、グラフ、統計を開始しました",
    ),
    "诊断模式已关闭：停止扩展字段和 CSV 写入": (
        "Diagnostic mode disabled: extended fields and CSV writing stopped",
        "診断モード無効：拡張項目とCSV書き込みを停止しました",
    ),
    "导出 CSV": ("Export CSV", "CSVを書き出す"),
    "还没有诊断数据。请先开启诊断模式。": ("No diagnostic data yet. Enable diagnostic mode first.", "診断データがありません。先に診断モードを有効にしてください。"),
    "导出心率 CSV": ("Export heart rate CSV", "心拍数CSVを書き出す"),
    "CSV 文件": ("CSV files", "CSVファイル"),
    "写入失败": ("Write failed", "書き込み失敗"),
    "更新检查失败 · 点击重试": ("Update check failed · click to retry", "更新確認に失敗 · クリックして再試行"),
    "现在": ("Now", "現在"),
    "等待真实心率数据": ("Waiting for real heart rate data", "実測心拍数データを待機中"),
    "模拟心率（非传感器）": ("Simulated heart rate (not sensor data)", "模擬心拍数（センサーデータではありません）"),
    "未知 BLE 错误": ("Unknown BLE error", "不明なBLEエラー"),
    "未知错误": ("Unknown error", "不明なエラー"),
    "电脑 BLE 直连已启动；正在扫描标准心率服务 0x180D": (
        "Direct PC BLE started; scanning standard Heart Rate service 0x180D",
        "PC直接BLEを開始し、標準心拍数サービス0x180Dをスキャン中",
    ),
    "电脑诊断：": ("PC diagnostics: ", "PC診断："),
    "真实心率超时，已发送 HRValid=false": (
        "Real heart rate timed out; sent HRValid=false",
        "実測心拍数がタイムアウトし、HRValid=falseを送信しました",
    ),
    "端口必须是整数": ("Port must be an integer", "ポートは整数で入力してください"),
    "端口必须在 1–65535 之间": ("Port must be between 1 and 65535", "ポートは1～65535の範囲で入力してください"),
    "不是有效的 VRChat 心率桥配对码": ("Not a valid VRChat Heart Rate Bridge pairing code", "有効なVRChat心拍数ブリッジのペアリングコードではありません"),
    "配对码端口无效": ("Invalid pairing-code port", "ペアリングコードのポートが無効です"),
    "配对地址必须是 IPv4": ("Pairing address must be IPv4", "ペアリングアドレスはIPv4である必要があります"),
    "配对端口无效": ("Invalid pairing port", "ペアリングポートが無効です"),
    "JSON 顶层必须是对象": ("The top-level JSON value must be an object", "JSONの最上位はオブジェクトである必要があります"),
    "字段 type 必须是非空字符串": ("Field type must be a non-empty string", "typeフィールドは空でない文字列である必要があります"),
    "sequence 不能为负数": ("sequence cannot be negative", "sequenceは負数にできません"),
    "sampleEpochMillis 必须大于 0": ("sampleEpochMillis must be greater than 0", "sampleEpochMillisは0より大きい必要があります"),
    "数据包不是有效 UTF-8": ("Packet is not valid UTF-8", "パケットは有効なUTF-8ではありません"),
    "数据包不是有效 JSON": ("Packet is not valid JSON", "パケットは有効なJSONではありません"),
    "已切换为手机 UDP 中转；电脑 BLE 已禁用": (
        "Switched to phone UDP relay; direct PC BLE is disabled",
        "スマートフォンUDP中継へ切替。PC直接BLEを無効にしました",
    ),
    "已切换为电脑直连小米手环；手机中转心率已禁用": (
        "Switched to direct Xiaomi Smart Band BLE; phone heart rate relay is disabled",
        "Xiaomi Smart Band直接BLEへ切替。スマートフォン中継を無効にしました",
    ),
    "数据正常": ("Data OK", "データ正常"),
    "手机 → 电脑诊断通过": ("Phone → PC diagnostic passed", "スマートフォン → PC診断成功"),
    "心率信号超时": ("Heart rate signal timed out", "心拍信号タイムアウト"),
    "无法打开日志文件夹": ("Unable to open log folder", "ログフォルダーを開けません"),
    "尚有未导出的 CSV 数据": ("Unexported CSV data", "未書き出しのCSVデータ"),
    "程序不会自动导出到用户文件，确定直接退出吗？": (
        "The app will not export it to a user file automatically. Exit anyway?",
        "ユーザーファイルへ自動書き出しされません。このまま終了しますか？",
    ),
    "切换语言会重新载入界面，确定继续吗？": (
        "Changing language reloads the interface. Continue?",
        "言語を変更すると画面を再読み込みします。続行しますか？",
    ),
}

_PHRASES: Final[dict[str, tuple[str, str]]] = {
    "最近 " : ("Last ", "直近"),
    " 分钟心率曲线": (" min heart rate chart", "分の心拍数グラフ"),
    " 分钟": (" min", "分"),
    "自动日志 · ": ("Automatic log · ", "自動ログ · "),
    "日志文件：" : ("Log file: ", "ログファイル："),
    "应用启动 · " : ("App started · ", "アプリ起動 · "),
    "应用退出": ("App exited", "アプリ終了"),
    "开始监听 UDP " : ("Listening on UDP ", "UDP待受開始 "),
    "监听 " : ("Listening on ", "待受中 "),
    "数据包 " : ("packet ", "パケット "),
    "端到端 " : ("end-to-end ", "エンドツーエンド "),
    "正在发送 " : ("Sending ", "送信中 "),
    "已发送 " : ("Sent ", "送信済み "),
    " 条" : (" rows", "件"),
    "发现新版本 " : ("New version ", "新しいバージョン "),
    " · 点击打开" : (" · click to open", " · クリックして開く"),
    "已是最新版 " : ("Up to date ", "最新版 "),
    "GitHub 有新版本：" : ("New GitHub version: ", "GitHubの新バージョン："),
    "GitHub 更新检查失败：" : ("GitHub update check failed: ", "GitHub更新確認失敗："),
    "BLE 操作失败：" : ("BLE operation failed: ", "BLE操作失敗："),
    "Windows BLE 扫描失败：" : ("Windows BLE scan failed: ", "Windows BLEスキャン失敗："),
    "BPM 超出有效范围：" : ("BPM is outside the valid range: ", "BPMが有効範囲外です："),
    "UDP 回执失败：" : ("UDP acknowledgement failed: ", "UDP応答失敗："),
    "UDP 接收失败：" : ("UDP receive failed: ", "UDP受信失敗："),
    "处理数据包失败：" : ("Packet processing failed: ", "パケット処理失敗："),
    "未知心率来源：" : ("Unknown heart rate source: ", "不明な心拍数ソース："),
    "字段 " : ("Field ", "フィールド "),
    " 必须是整数" : (" must be an integer", " は整数である必要があります"),
    "找到 " : ("Found ", "検出 "),
    " 个心率设备" : (" heart rate device(s)", "件の心拍数デバイス"),
    "正在连接 " : ("Connecting to ", "接続中 "),
    "已连接 " : ("Connected to ", "接続済み "),
    "，等待心率" : (", waiting for heart rate", "、心拍数を待機中"),
    "启动失败：" : ("Start failed: ", "開始失敗："),
    "保存设置失败：" : ("Failed to save settings: ", "設定保存失敗："),
    "保存心率来源失败：" : ("Failed to save heart rate source: ", "心拍数ソース保存失敗："),
    "保存 BLE 设备失败：" : ("Failed to save BLE device: ", "BLEデバイス保存失敗："),
    "诊断 CSV 写入失败：" : ("Diagnostic CSV write failed: ", "診断CSV書き込み失敗："),
    "无法创建诊断 CSV：" : ("Unable to create diagnostic CSV: ", "診断CSVを作成できません："),
    "写入失败：" : ("Write failed: ", "書き込み失敗："),
    "已导出 " : ("Exported ", "書き出し済み "),
    "CSV 追加写入 · " : ("CSV rows · ", "CSV行 · "),
    "已打开日志文件夹：" : ("Opened log folder: ", "ログフォルダーを開きました："),
    "打开日志文件夹失败：" : ("Failed to open log folder: ", "ログフォルダーを開けません："),
    "CSV 已手动导出：" : ("CSV exported manually: ", "CSVを手動で書き出しました："),
    "Tk 回调异常\n" : ("Tk callback exception\n", "Tkコールバック例外\n"),
    " 条数据。" : (" rows.", "件のデータ。"),
    "界面操作异常：" : ("UI error: ", "UIエラー："),
    "还有 " : ("There are ", "残り "),
    " 条记录未导出。" : (" unexported rows.", "件が未書き出しです。"),
}
