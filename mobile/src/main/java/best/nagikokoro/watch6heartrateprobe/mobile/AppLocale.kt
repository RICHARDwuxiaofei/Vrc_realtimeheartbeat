package best.nagikokoro.watch6heartrateprobe.mobile

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

enum class AppLanguage(val preferenceValue: String, val languageTag: String?) {
    SYSTEM("system", null),
    CHINESE("zh", "zh-CN"),
    ENGLISH("en", "en"),
    JAPANESE("ja", "ja"),
}

object AppLocale {
    private const val PREFS = "app_locale"
    private const val KEY_LANGUAGE = "language"

    fun selected(context: Context): AppLanguage {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, AppLanguage.SYSTEM.preferenceValue)
        return AppLanguage.entries.firstOrNull { it.preferenceValue == value } ?: AppLanguage.SYSTEM
    }

    fun wrap(base: Context): Context {
        val tag = selected(base).languageTag ?: return base
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(tag))
        return ContextWrapper(base.createConfigurationContext(configuration))
    }

    fun apply(activity: Activity, language: AppLanguage) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, language.preferenceValue)
            .apply()
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                language.languageTag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
        } else {
            activity.recreate()
        }
    }

    fun effectiveLanguage(context: Context): AppLanguage {
        val selected = selected(context)
        if (selected != AppLanguage.SYSTEM) return selected
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        return when (locale.language.lowercase(Locale.ROOT)) {
            "ja" -> AppLanguage.JAPANESE
            "zh" -> AppLanguage.CHINESE
            else -> AppLanguage.ENGLISH
        }
    }

    fun text(context: Context, source: String): String =
        PhoneTranslations.translate(source, effectiveLanguage(context))
}

internal object PhoneTranslations {
    private data class Translation(val en: String, val ja: String)

    private val exact = mapOf(
        "语言" to Translation("Language", "言語"),
        "跟随系统" to Translation("Follow system", "システムに従う"),
        "简体中文" to Translation("简体中文", "簡体字中国語"),
        "心率中转站" to Translation("Heart Rate Relay", "心拍数リレー"),
        "手机心率通知" to Translation("Phone heart rate", "スマートフォン心拍数"),
        "当前心率" to Translation("Current heart rate", "現在の心拍数"),
        "每五秒更新一次当前心率" to Translation("Updates the current heart rate every five seconds", "現在の心拍数を5秒ごとに更新"),
        "心率来源" to Translation("Heart rate source", "心拍数ソース"),
        "链路状态" to Translation("Connection status", "接続状態"),
        "小米手环" to Translation("Xiaomi Smart Band", "Xiaomi Smart Band"),
        "小米手环 BLE" to Translation("Xiaomi Smart Band BLE", "Xiaomi Smart Band BLE"),
        "模拟心率（非传感器）" to Translation("Simulated heart rate (not sensor data)", "模擬心拍数（センサーデータではありません）"),
        "等待手表心率" to Translation("Waiting for watch heart rate", "Watchの心拍数を待機中"),
        "等待Galaxy Watch数据" to Translation("Waiting for Galaxy Watch data", "Galaxy Watchのデータを待機中"),
        "等待小米手环 BLE数据" to Translation("Waiting for Xiaomi Smart Band BLE data", "Xiaomi Smart Band BLEのデータを待機中"),
        "这台手机" to Translation("This phone", "このスマートフォン"),
        "Windows 接收器" to Translation("Windows receiver", "Windowsレシーバー"),
        "已暂停发送到电脑" to Translation("PC relay paused", "PCへの転送を一時停止"),
        "电脑已确认" to Translation("PC confirmed", "PC確認済み"),
        "等待电脑回执" to Translation("Waiting for PC acknowledgement", "PC確認を待機中"),
        "电脑地址" to Translation("PC address", "PCアドレス"),
        "扫码配对电脑" to Translation("Scan to pair PC", "QRコードでPCとペアリング"),
        "扫描电脑端显示的配对二维码" to Translation("Scan the pairing QR code shown on the PC", "PCに表示されたペアリングQRコードを読み取ります"),
        "电脑 IPv4" to Translation("PC IPv4", "PC IPv4"),
        "例如 192.168.100.188" to Translation("Example: 192.168.100.188", "例：192.168.100.188"),
        "UDP 端口" to Translation("UDP port", "UDPポート"),
        "保存设置" to Translation("Save settings", "設定を保存"),
        "手机 → 电脑一键诊断" to Translation("Test phone → PC", "スマートフォン → PCを診断"),
        "诊断中…" to Translation("Testing…", "診断中…"),
        "配对码无法识别" to Translation("Pairing code not recognized", "ペアリングコードを認識できません"),
        "完整诊断数据" to Translation("Full diagnostic data", "詳細診断データ"),
        "手表样本" to Translation("Watch samples", "Watchサンプル"),
        "未转发样本" to Translation("Throttled samples", "未転送サンプル"),
        "已发往电脑" to Translation("Sent to PC", "PCへ送信"),
        "电脑确认" to Translation("PC acknowledgements", "PC確認"),
        "原始 BPM" to Translation("Raw BPM", "元のBPM"),
        "传感器精度" to Translation("Sensor accuracy", "センサー精度"),
        "手表电量" to Translation("Watch battery", "Watchバッテリー"),
        "手表屏幕" to Translation("Watch screen", "Watch画面"),
        "亮屏" to Translation("On", "オン"),
        "息屏" to Translation("Off", "オフ"),
        "手表发送模式" to Translation("Watch relay mode", "Watch送信モード"),
        "数据性质" to Translation("Data type", "データ種別"),
        "模拟（非传感器）" to Translation("Simulated (not sensor)", "模擬（非センサー）"),
        "真实传感器" to Translation("Real sensor", "実センサー"),
        "BLE 设备" to Translation("BLE device", "BLEデバイス"),
        "BLE 地址" to Translation("BLE address", "BLEアドレス"),
        "手表发送间隔" to Translation("Watch relay interval", "Watch送信間隔"),
        "手机网络" to Translation("Phone network", "スマートフォンのネットワーク"),
        "手机局域网 IP" to Translation("Phone LAN IP", "スマートフォンLAN IP"),
        "最近手表数据" to Translation("Latest watch data", "最新Watchデータ"),
        "最近电脑确认" to Translation("Latest PC acknowledgement", "最新PC確認"),
        "当前目标" to Translation("Current target", "現在の送信先"),
        "未设置" to Translation("Not set", "未設定"),
        "检测到 VPN。电脑回执失败时，请允许局域网访问或暂时关闭 VPN。" to Translation(
            "VPN detected. If PC acknowledgement fails, allow LAN access or temporarily disable the VPN.",
            "VPNを検出しました。PC確認に失敗する場合はLANアクセスを許可するか、一時的にVPNを無効にしてください。",
        ),
        "小米模式只在启用时运行 BLE 前台服务；这里的发送间隔只控制手机到电脑。切回 Galaxy Watch 后会立即停止 BLE 扫描和连接。" to Translation(
            "Xiaomi mode runs a BLE foreground service only while enabled. This interval controls phone-to-PC relay; switching back to Galaxy Watch stops BLE scanning and connection immediately.",
            "Xiaomiモードは有効時のみBLEフォアグラウンドサービスを実行します。この間隔はスマートフォンからPCへの転送のみを制御し、Galaxy Watchへ戻すとBLEスキャンと接続を直ちに停止します。",
        ),
        "手表决定新心率多久到达手机；这里的发送间隔只控制手机到电脑。暂停后手机仍继续接收手表数据。" to Translation(
            "The watch controls how often new heart rate data reaches the phone. This interval only controls phone-to-PC relay; pausing still allows watch data reception.",
            "新しい心拍数がスマートフォンへ届く頻度はWatch側で決まります。この間隔はスマートフォンからPCへの転送のみを制御し、一時停止中もWatchデータは受信します。",
        ),
        "诊断模式" to Translation("Diagnostic mode", "診断モード"),
        "采集 BLE 设备与完整链路字段；电脑开关会同步到手机" to Translation(
            "Collects BLE device and full relay fields; the PC switch syncs to the phone",
            "BLEデバイスと完全な転送情報を収集し、PCの切替をスマートフォンへ同期します",
        ),
        "采集手表扩展字段并显示完整链路数据；电脑开关会同步" to Translation(
            "Collects extended watch fields and shows full relay data; the PC switch is synchronized",
            "Watchの拡張情報と完全な転送データを表示し、PCの切替を同期します",
        ),
        "普通模式：只保留心率中转所需数据；电脑端为主开关" to Translation(
            "Standard mode keeps only data needed for heart rate relay; the PC is the primary switch",
            "通常モードでは心拍数転送に必要なデータのみ保持し、PC側が主スイッチになります",
        ),
        "切换至小米手环" to Translation("Switch to Xiaomi Smart Band", "Xiaomi Smart Bandへ切替"),
        "支持小米手环 10 等提供标准 BLE 心率广播的设备。切换前请在手环打开：设置 → 共享心率 → 开启。" to Translation(
            "Supports devices such as Xiaomi Smart Band 10 that expose the standard BLE Heart Rate service. On the band, enable Settings → Share heart rate first.",
            "標準BLE心拍数サービスを提供するXiaomi Smart Band 10などに対応します。先にバンドで「設定 → 心拍数の共有」を有効にしてください。",
        ),
        "扫描中…" to Translation("Scanning…", "スキャン中…"),
        "重新扫描" to Translation("Scan again", "再スキャン"),
        "切回 Galaxy Watch" to Translation("Switch back to Galaxy Watch", "Galaxy Watchへ戻す"),
        "只扫描标准心率服务，不读取小米账号或历史健康数据。首次找到设备后点一下设备；以后会记住并自动重连。" to Translation(
            "Only the standard Heart Rate service is scanned. Xiaomi account and health history are never read. Select the device once; it will be remembered and reconnected automatically.",
            "標準心拍数サービスのみをスキャンし、Xiaomiアカウントや健康履歴は読み取りません。初回にデバイスを選ぶと記憶され、以後は自動再接続します。",
        ),
        "发送控制" to Translation("Relay control", "転送制御"),
        "手机 → 电脑" to Translation("Phone → PC", "スマートフォン → PC"),
        "已暂停，手机仍继续接收" to Translation("Paused; phone still receives data", "一時停止中もスマートフォンは受信を継続"),
        "运行中" to Translation("Running", "実行中"),
        "已暂停" to Translation("Paused", "一時停止"),
        "由 BLE 广播决定" to Translation("Controlled by BLE broadcast", "BLEブロードキャストに依存"),
        "等待手表上报" to Translation("Waiting for watch report", "Watchからの報告を待機中"),
        "手表与手机同步发送间隔" to Translation("Watch and phone relay interval", "Watchとスマートフォンの送信間隔"),
        "电脑、手机或手表任意一端选择，另外两端会自动同步；运行中也可切换。" to Translation(
            "Choose on the PC, phone, or watch; the other two sync automatically, even while running.",
            "PC、スマートフォン、Watchのいずれかで選ぶと、残り2つへ自動同期されます。動作中も変更できます。",
        ),
        "暂停发送到电脑" to Translation("Pause PC relay", "PCへの転送を一時停止"),
        "恢复发送到电脑" to Translation("Resume PC relay", "PCへの転送を再開"),
        "附近设备权限被拒绝，无法读取小米手环心率" to Translation(
            "Nearby devices permission was denied; Xiaomi Smart Band heart rate cannot be read",
            "付近のデバイス権限が拒否されたため、Xiaomi Smart Bandの心拍数を読み取れません",
        ),
        "等待小米手环" to Translation("Waiting for Xiaomi Smart Band", "Xiaomi Smart Bandを待機中"),
        "尚未启用" to Translation("Not enabled", "未有効"),
        "尚未诊断" to Translation("Not tested", "未診断"),
        "诊断模式未开启" to Translation("Diagnostic mode is off", "診断モードが無効です"),
        "诊断模式已开启，等待链路数据" to Translation("Diagnostic mode enabled; waiting for relay data", "診断モード有効。転送データを待機中"),
        "扫描结束；请确认手环已开启“设置 → 共享心率”" to Translation(
            "Scan finished. Confirm Settings → Share heart rate is enabled on the band.",
            "スキャン完了。バンドで「設定 → 心拍数の共有」が有効か確認してください。",
        ),
        "未找到心率广播" to Translation("No heart rate broadcast found", "心拍数ブロードキャストが見つかりません"),
        "请先打开手机蓝牙" to Translation("Turn on phone Bluetooth first", "先にスマートフォンのBluetoothをオンにしてください"),
        "需要蓝牙/附近设备权限" to Translation("Bluetooth / Nearby devices permission required", "Bluetooth／付近のデバイス権限が必要です"),
        "这台手机不支持 BLE 扫描" to Translation("This phone does not support BLE scanning", "このスマートフォンはBLEスキャンに対応していません"),
        "连接已断开，准备重连…" to Translation("Disconnected; preparing to reconnect…", "切断されました。再接続を準備中…"),
        "已切回 Galaxy Watch" to Translation("Switched back to Galaxy Watch", "Galaxy Watchへ戻しました"),
        "准备连接小米手环" to Translation("Preparing Xiaomi Smart Band connection", "Xiaomi Smart Bandへの接続を準備中"),
        "移动网络" to Translation("Mobile network", "モバイルネットワーク"),
        "以太网" to Translation("Ethernet", "イーサネット"),
        "其他网络" to Translation("Other network", "その他のネットワーク"),
        "未连接" to Translation("Disconnected", "未接続"),
        "BLE 心率设备" to Translation("BLE heart rate device", "BLE心拍数デバイス"),
        "VRChat 心率桥 · 小米手环" to Translation("VRChat Heart Rate Bridge · Xiaomi Smart Band", "VRChat心拍数ブリッジ · Xiaomi Smart Band"),
        "不是 VRChat 心率桥配对码" to Translation("Not a VRChat Heart Rate Bridge pairing code", "VRChat心拍数ブリッジのペアリングコードではありません"),
        "仅在小米手环模式开启时保持 BLE 心率连接" to Translation("Keeps the BLE heart rate connection only while Xiaomi mode is enabled", "Xiaomiモード有効時のみBLE心拍数接続を維持します"),
        "同步手表发送频率失败：未发现可达手表" to Translation("Failed to sync watch relay rate: no reachable watch found", "Watch送信頻度の同期に失敗：到達可能なWatchが見つかりません"),
        "启动心率通知失败" to Translation("Failed to start heart rate notifications", "心拍数通知の開始に失敗"),
        "失败" to Translation("Failed", "失敗"),
        "失败：发送到电脑已暂停" to Translation("Failed: PC relay is paused", "失敗：PCへの転送は一時停止中"),
        "失败：尚未设置电脑 IP" to Translation("Failed: PC IP is not set", "失敗：PC IPが未設定"),
        "失败：手机没有可用的局域网 IPv4" to Translation("Failed: the phone has no usable LAN IPv4 address", "失敗：スマートフォンに利用可能なLAN IPv4アドレスがありません"),
        "失败：请先开启诊断模式" to Translation("Failed: enable diagnostic mode first", "失敗：先に診断モードを有効にしてください"),
        "小米手环心率连接" to Translation("Xiaomi Smart Band heart rate connection", "Xiaomi Smart Band心拍数接続"),
        "已连接，正在读取心率服务…" to Translation("Connected; reading Heart Rate service…", "接続済み。心拍数サービスを読み取り中…"),
        "手机 VPN 阻止了局域网 UDP（EPERM）。请在 VPN 中开启“绕过局域网/允许局域网”，或测试时关闭 VPN" to Translation(
            "The phone VPN blocked LAN UDP (EPERM). Allow/bypass LAN traffic in the VPN, or disable the VPN while testing.",
            "スマートフォンのVPNがLAN UDP（EPERM）を遮断しました。VPNでLAN通信を許可するか、テスト中はVPNを無効にしてください。",
        ),
        "扫描失败" to Translation("Scan failed", "スキャン失敗"),
        "收到无效的 BLE 心率数据" to Translation("Received invalid BLE heart rate data", "無効なBLE心拍数データを受信しました"),
        "无效的蓝牙设备地址" to Translation("Invalid Bluetooth device address", "無効なBluetoothデバイスアドレス"),
        "无法订阅心率通知" to Translation("Unable to subscribe to heart rate notifications", "心拍数通知を購読できません"),
        "正在扫描心率广播…" to Translation("Scanning for heart rate broadcasts…", "心拍数ブロードキャストをスキャン中…"),
        "正在接收实时心率" to Translation("Receiving live heart rate", "リアルタイム心拍数を受信中"),
        "没有可连接的小米手环地址" to Translation("No Xiaomi Smart Band address is available to connect", "接続可能なXiaomi Smart Bandアドレスがありません"),
        "电脑 IP 未设置" to Translation("PC IP is not set", "PC IPが未設定"),
        "电脑回执内容不匹配" to Translation("PC acknowledgement did not match", "PC確認内容が一致しません"),
        "系统拒绝局域网 UDP（EPERM）。请检查“附近设备/局域网”权限和系统网络限制" to Translation(
            "The system denied LAN UDP (EPERM). Check Nearby devices/LAN permission and system network restrictions.",
            "システムがLAN UDP（EPERM）を拒否しました。付近のデバイス／LAN権限とネットワーク制限を確認してください。",
        ),
        "设备没有标准心率测量特征（0x2A37）" to Translation("The device does not expose the standard Heart Rate Measurement characteristic (0x2A37)", "デバイスに標準心拍数測定特性（0x2A37）がありません"),
        "设备缺少心率通知配置" to Translation("The device is missing heart rate notification configuration", "デバイスに心拍数通知設定がありません"),
        "诊断已取消：发送到电脑已暂停" to Translation("Diagnostic cancelled: PC relay is paused", "診断を中止：PCへの転送は一時停止中"),
        "请先填写电脑 IP" to Translation("Enter the PC IP first", "先にPC IPを入力してください"),
        "连接断开" to Translation("Disconnected", "切断"),
        "通过" to Translation("Passed", "成功"),
        "配对码格式错误" to Translation("Invalid pairing-code format", "ペアリングコード形式エラー"),
        "配对码电脑地址无效" to Translation("Invalid PC address in pairing code", "ペアリングコードのPCアドレスが無効です"),
        "配对码端口无效" to Translation("Invalid port in pairing code", "ペアリングコードのポートが無効です"),
        "手机已选 1 秒，但手表当前约 5 秒才产生一份新数据。请在手表停止传输后切到“1 秒实时”。" to Translation(
            "The phone is set to 1 s, but the watch currently produces new data about every 5 s. Stop watch relay, then select “1 s real-time” on the watch.",
            "スマートフォンは1秒ですが、Watchは約5秒ごとに新しいデータを生成しています。Watchの転送を停止して「1秒 リアルタイム」を選択してください。",
        ),
    )

    private val phrases = mapOf(
        " 秒前" to Translation(" s ago", "秒前"),
        " 秒发送" to Translation(" s", "秒ごとに送信"),
        " 秒" to Translation(" s", "秒"),
        "配对成功：" to Translation("Paired: ", "ペアリング成功："),
        "配对成功" to Translation("Paired", "ペアリング成功"),
        "正常 · 序号 " to Translation(" OK · sequence ", " 正常 · シーケンス "),
        "正常" to Translation(" OK", " 正常"),
        "模拟数据 · " to Translation("Simulated · ", "模擬データ · "),
        "采样于 " to Translation("Sampled at ", "サンプル時刻 "),
        "约 " to Translation("About ", "約"),
        "正在测试 " to Translation("Testing ", "テスト中 "),
        "通过：电脑 " to Translation("Passed: PC ", "成功：PC "),
        " 已回执" to Translation(" acknowledged", " が確認"),
        "失败：" to Translation("Failed: ", "失敗："),
        "正在扫描" to Translation("Scanning ", "スキャン中 "),
        "正在连接 " to Translation("Connecting to ", "接続中 "),
        "已连接 " to Translation("Connected to ", "接続済み "),
        "实时心率 " to Translation("Live heart rate ", "リアルタイム心拍数 "),
        "同步手表发送频率失败：" to Translation("Failed to sync watch relay rate: ", "Watch送信頻度の同期に失敗："),
        "查找手表同步节点失败：" to Translation("Failed to find watch sync node: ", "Watch同期ノードの検索に失敗："),
        "手表数据格式错误：" to Translation("Invalid watch data: ", "Watchデータ形式エラー："),
        "手表控制指令格式错误：" to Translation("Invalid watch control message: ", "Watch制御メッセージ形式エラー："),
        "回传手表失败：" to Translation("Failed to acknowledge watch: ", "Watchへの応答に失敗："),
        " · VPN 已开启" to Translation(" · VPN active", " · VPN有効"),
        " → 手机" to Translation(" → Phone", " → スマートフォン"),
        "BLE 扫描失败（代码 " to Translation("BLE scan failed (code ", "BLEスキャン失敗（コード "),
        "同步手表诊断模式失败：" to Translation("Failed to sync watch diagnostic mode: ", "Watch診断モードの同期に失敗："),
        "订阅心率通知失败（代码 " to Translation("Heart rate notification subscription failed (code ", "心拍数通知の購読に失敗（コード "),
        "读取 BLE 服务失败（代码 " to Translation("BLE service discovery failed (code ", "BLEサービス読み取り失敗（コード "),
        "电脑已确认 · 每 " to Translation("PC confirmed · every ", "PC確認済み · "),
        "电脑未回执：" to Translation("No PC acknowledgement: ", "PC確認なし："),
        "等待电脑回执 · 每 " to Translation("Waiting for PC acknowledgement · every ", "PC確認待機 · "),
        "等待" to Translation("Waiting for ", "待機中："),
        "数据" to Translation(" data", "データ"),
        "手机已选 " to Translation("Phone is set to ", "スマートフォンは"),
        " 秒，但手表当前约 " to Translation(" s, but the watch produces new data about every ", "秒ですが、Watchは約"),
        " 秒才产生一份新数据。请在手表停止传输后切到“1 秒实时”。" to Translation(
            " s. Stop watch relay, then select “1 s real-time” on the watch.",
            "秒ごとに新しいデータを生成しています。Watchの転送を停止して「1秒 リアルタイム」を選択してください。",
        ),
    )

    fun translate(source: String, language: AppLanguage): String {
        if (language == AppLanguage.CHINESE) return source
        exact[source]?.let { return if (language == AppLanguage.JAPANESE) it.ja else it.en }
        var translated = source
        (exact + phrases).entries
            .sortedByDescending { it.key.length }
            .forEach { (key, value) ->
                if (translated.contains(key)) {
                    translated = translated.replace(key, if (language == AppLanguage.JAPANESE) value.ja else value.en)
                }
            }
        return translated
    }
}
