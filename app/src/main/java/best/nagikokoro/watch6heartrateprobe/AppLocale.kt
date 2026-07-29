package best.nagikokoro.watch6heartrateprobe

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
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, AppLanguage.SYSTEM.preferenceValue)
        return AppLanguage.entries.firstOrNull { it.preferenceValue == stored } ?: AppLanguage.SYSTEM
    }

    fun wrap(base: Context): Context {
        val selected = selected(base)
        val tag = selected.languageTag ?: return base
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
        WatchTranslations.translate(source, effectiveLanguage(context))
}

internal object WatchTranslations {
    private data class Translation(val en: String, val ja: String)

    private val exact = mapOf(
        "语言" to Translation("Language", "言語"),
        "跟随系统" to Translation("System", "システム"),
        "简体中文" to Translation("简体中文", "簡体字中国語"),
        "心率传输" to Translation("Heart Rate Relay", "心拍数転送"),
        "Galaxy Watch → 手机" to Translation("Galaxy Watch → Phone", "Galaxy Watch → スマートフォン"),
        "正在等待心率" to Translation("Waiting for heart rate", "心拍数を待機中"),
        "心率信号暂时中断" to Translation("Heart rate signal interrupted", "心拍信号が一時中断"),
        "后台传输中" to Translation("Relaying in background", "バックグラウンドで転送中"),
        "手机" to Translation("Phone", "スマートフォン"),
        "等待连接" to Translation("Waiting for connection", "接続待機中"),
        "数据" to Translation("Data", "データ"),
        "频率" to Translation("Rate", "頻度"),
        "电量" to Translation("Battery", "バッテリー"),
        "停止传输" to Translation("Stop relay", "転送を停止"),
        "开始传输" to Translation("Start relay", "転送を開始"),
        "返回表盘或息屏后仍会继续运行" to Translation(
            "Continues after returning to the watch face or turning the screen off",
            "文字盤に戻るか画面を消しても動作を続けます",
        ),
        "发送频率" to Translation("Relay rate", "送信頻度"),
        "1 秒实时" to Translation("1 s real-time", "1秒 リアルタイム"),
        "5 秒省电" to Translation("5 s balanced", "5秒 省電"),
        "10 秒超省电" to Translation("10 s power saver", "10秒 超省電"),
        "息屏心率节能传输" to Translation("Screen-off heart rate relay", "画面オフ心拍数省電転送"),
        "按所选的 1 秒实时或 5 秒省电模式传输心率" to Translation(
            "Relays heart rate using the selected 1 s, 5 s, or 10 s mode",
            "選択した1秒・5秒・10秒モードで心拍数を転送します",
        ),
        "约每秒更新，息屏时保持处理器活跃，耗电更高" to Translation(
            "Updates about once per second; uses more power with the screen off",
            "約1秒ごとに更新。画面オフ時の消費電力が増えます",
        ),
        "心率变化约 5 秒送达，稳定时自动降低重复传输" to Translation(
            "Changes arrive in about 5 seconds; duplicate relays are reduced while stable",
            "変化は約5秒で届き、安定時は重複転送を減らします",
        ),
        "减少手表与手机通信，适合长时间挂 VRChat" to Translation(
            "Reduces watch-to-phone traffic for long VRChat sessions",
            "Watchとスマートフォン間の通信を抑え、長時間のVRChatに適します",
        ),
        "选择后会自动同步到手机" to Translation(
            "The phone is updated automatically",
            "選択内容はスマートフォンへ自動同期されます",
        ),
        "PulseLink Watch" to Translation("PulseLink Watch", "PulseLink Watch"),
        "息屏实时心率" to Translation("Screen-off heart rate", "画面オフ心拍数"),
        "前台对照" to Translation("Foreground reference", "フォアグラウンド比較"),
        "后台连续" to Translation("Background continuous", "バックグラウンド連続"),
        "✓ 前台对照" to Translation("✓ Foreground", "✓ フォアグラウンド"),
        "✓ 后台连续" to Translation("✓ Background", "✓ バックグラウンド"),
        "开始后台节能心率（约 5 秒）" to Translation("Start background heart rate (~5 s)", "バックグラウンド心拍数を開始（約5秒）"),
        "停止息屏实时心率" to Translation("Stop screen-off heart rate", "画面オフ心拍数を停止"),
        "开始前台对照测量" to Translation("Start foreground reference", "フォアグラウンド比較を開始"),
        "停止前台对照测量" to Translation("Stop foreground reference", "フォアグラウンド比較を停止"),
        "授予心率权限" to Translation("Grant heart rate permission", "心拍数の権限を許可"),
        "息屏交付与续航测试" to Translation("Screen-off delivery and battery test", "画面オフ配信・バッテリーテスト"),
        "正常佩戴" to Translation("On wrist", "装着中"),
        "未佩戴" to Translation("Off wrist", "未装着"),
        "✓ 正常佩戴" to Translation("✓ On wrist", "✓ 装着中"),
        "✓ 未佩戴" to Translation("✓ Off wrist", "✓ 未装着"),
        "测试正在进行" to Translation("Test in progress", "テスト実行中"),
        "请先点击“开始后台节能心率（约 5 秒）”" to Translation(
            "Start background heart rate first",
            "先にバックグラウンド心拍数を開始してください",
        ),
        "正在启动后台会话" to Translation("Starting background session", "バックグラウンドセッションを開始中"),
        "正在注册心率回调" to Translation("Registering heart rate callback", "心拍数コールバックを登録中"),
        "等待至少 5 个真实样本" to Translation("Waiting for at least 5 real samples", "実測サンプルを5件以上待機中"),
        "已经就绪，可以选择测试时长" to Translation("Ready; select a test duration", "準備完了。テスト時間を選択してください"),
        "计时已结束：先保持息屏5分钟，再亮屏等待尾部缓存" to Translation(
            "Timer ended: keep the screen off for 5 minutes, then turn it on and wait for buffered data",
            "計測終了：5分間画面を消したままにし、その後点灯して遅延データを待ってください",
        ),
        "息屏测试\n10分钟" to Translation("Screen off\n10 min", "画面オフ\n10分"),
        "快速续航\n20分钟" to Translation("Battery\n20 min", "バッテリー\n20分"),
        "正式续航测试（60分钟）" to Translation("Formal battery test (60 min)", "正式バッテリーテスト（60分）"),
        "实时交付实验（10分钟，较耗电）" to Translation("Real-time delivery test (10 min, higher drain)", "リアルタイム配信テスト（10分・高消費）"),
        "提前结束当前测试" to Translation("End current test early", "現在のテストを早期終了"),
        "刷新上次测试结果" to Translation("Refresh last test result", "前回のテスト結果を更新"),
        "详细状态" to Translation("Detailed status", "詳細ステータス"),
        "模拟链路（非传感器）" to Translation("Simulated relay (not sensor data)", "模擬転送（センサーデータではありません）"),
        "生成 60–80 BPM 平缓波动，并作为 heart_rate 进入手机、电脑和 OSC，仅用于链路测试。" to Translation(
            "Generates a smooth 60–80 BPM signal and relays it to the phone, PC, and OSC for connection testing only.",
            "60～80 BPMの緩やかな模擬信号を生成し、接続テスト専用としてスマートフォン、PC、OSCへ転送します。",
        ),
        "开始模拟心率 60–80 BPM" to Translation("Start simulated 60–80 BPM", "模擬心拍数 60～80 BPMを開始"),
        "停止模拟心率" to Translation("Stop simulated heart rate", "模擬心拍数を停止"),
        "手机/电脑中继测试（可选）" to Translation("Phone/PC relay test (optional)", "スマートフォン／PC転送テスト（任意）"),
        "清除屏幕日志" to Translation("Clear on-screen log", "画面ログを消去"),
        "暂无日志" to Translation("No log entries", "ログはありません"),
        "准备状态" to Translation("Readiness", "準備状態"),
        "后台测试状态" to Translation("Background test", "バックグラウンドテスト"),
        "测试场景" to Translation("Scenario", "シナリオ"),
        "测试类型" to Translation("Test type", "テスト種別"),
        "测试耗时" to Translation("Elapsed", "経過時間"),
        "收尾状态" to Translation("Finalizing", "終了処理"),
        "测试样本" to Translation("Test samples", "テストサンプル"),
        "回调批次" to Translation("Callback batches", "コールバック回数"),
        "息屏交付样本" to Translation("Screen-off samples", "画面オフ時サンプル"),
        "息屏交付回调" to Translation("Screen-off callbacks", "画面オフ時コールバック"),
        "最长无回调" to Translation("Longest callback gap", "最長コールバック間隔"),
        "服务 / 进程重启" to Translation("Service / process restarts", "サービス／プロセス再起動"),
        "屏幕开 / 关" to Translation("Screen on / off", "画面オン／オフ"),
        "测试电量" to Translation("Test battery", "テスト時バッテリー"),
        "本地报告" to Translation("Local report", "ローカルレポート"),
        "已生成，可在电脑导出" to Translation("Generated; export it on the PC", "生成済み。PCから書き出せます"),
        "测试警告" to Translation("Test warnings", "テスト警告"),
        "测试编号" to Translation("Test ID", "テストID"),
        "当前模式" to Translation("Current mode", "現在のモード"),
        "后台服务" to Translation("Background service", "バックグラウンドサービス"),
        "后台会话" to Translation("Background session", "バックグラウンドセッション"),
        "运动类型" to Translation("Exercise type", "運動種別"),
        "前台测量状态" to Translation("Foreground measurement", "フォアグラウンド測定"),
        "前台回调" to Translation("Foreground callback", "フォアグラウンドコールバック"),
        "后台回调" to Translation("Background callback", "バックグラウンドコールバック"),
        "屏幕可交互" to Translation("Screen interactive", "画面操作可能"),
        "环境显示模式" to Translation("Ambient mode", "アンビエントモード"),
        "页面状态" to Translation("Activity state", "画面状態"),
        "进程 PID" to Translation("Process PID", "プロセスPID"),
        "前台心率权限" to Translation("Foreground HR permission", "フォアグラウンド心拍権限"),
        "后台健康权限" to Translation("Background health permission", "バックグラウンド健康権限"),
        "手机蓝牙中转" to Translation("Phone Bluetooth relay", "スマートフォンBluetooth転送"),
        "已发 / 失败" to Translation("Sent / failed", "送信／失敗"),
        "电脑回执" to Translation("PC acknowledgement", "PC確認"),
        "远端诊断模式" to Translation("Remote diagnostic mode", "リモート診断モード"),
        "样本数" to Translation("Samples", "サンプル数"),
        "最后更新时间" to Translation("Last update", "最終更新"),
        "数据年龄" to Translation("Data age", "データ経過時間"),
        "数据超时次数" to Translation("Timeouts", "タイムアウト回数"),
        "最大断档" to Translation("Largest gap", "最大欠落時間"),
        "开始 / 当前电量" to Translation("Start / current battery", "開始／現在のバッテリー"),
        "会话持续时间" to Translation("Session duration", "セッション時間"),
        "数据可用性" to Translation("Data availability", "データ可用性"),
        "后台测量错误" to Translation("Background measurement error", "バックグラウンド測定エラー"),
        "结束原因" to Translation("End reason", "終了理由"),
        "是" to Translation("Yes", "はい"),
        "否" to Translation("No", "いいえ"),
        "未启动" to Translation("Not started", "未開始"),
        "正在恢复" to Translation("Restoring", "復元中"),
        "正在启动" to Translation("Starting", "開始中"),
        "运行中" to Translation("Running", "実行中"),
        "已暂停" to Translation("Paused", "一時停止"),
        "正在停止" to Translation("Stopping", "停止中"),
        "正在结束" to Translation("Ending", "終了中"),
        "已结束" to Translation("Ended", "終了"),
        "错误" to Translation("Error", "エラー"),
        "未开始" to Translation("Not started", "未開始"),
        "进行中" to Translation("In progress", "実行中"),
        "已完成" to Translation("Completed", "完了"),
        "已提前停止" to Translation("Stopped early", "早期終了"),
        "异常终止" to Translation("Abnormal termination", "異常終了"),
        "综合训练" to Translation("Workout", "ワークアウト"),
        "步行" to Translation("Walking", "ウォーキング"),
        "课程训练" to Translation("Exercise class", "エクササイズクラス"),
        "力量训练" to Translation("Strength training", "筋力トレーニング"),
        "可用（已佩戴）" to Translation("Available (on wrist)", "利用可能（装着中）"),
        "不可用（未佩戴）" to Translation("Unavailable (off wrist)", "利用不可（未装着）"),
        "正在获取心率" to Translation("Acquiring heart rate", "心拍数を取得中"),
        "暂时不可用" to Translation("Temporarily unavailable", "一時的に利用不可"),
        "未知" to Translation("Unknown", "不明"),
        "已创建" to Translation("Created", "作成済み"),
        "已显示" to Translation("Visible", "表示中"),
        "正在前台" to Translation("Foreground", "フォアグラウンド"),
        "已进入后台" to Translation("Background", "バックグラウンド"),
        "已销毁" to Translation("Destroyed", "破棄済み"),
        "进程已启动" to Translation("Process started", "プロセス開始済み"),
        "用户停止" to Translation("Stopped by user", "ユーザーが停止"),
        "测试时间到" to Translation("Test duration reached", "テスト時間終了"),
        "用户提前结束测试" to Translation("Ended early by user", "ユーザーが早期終了"),
        "已授权" to Translation("Granted", "許可済み"),
        "未授权" to Translation("Not granted", "未許可"),
        "已永久拒绝" to Translation("Permanently denied", "永続的に拒否"),
        "等待" to Translation("Waiting", "待機中"),
        "已确认" to Translation("Confirmed", "確認済み"),
        "等待手机" to Translation("Waiting for phone", "スマートフォンを待機中"),
        "10 分钟息屏交付测试" to Translation("10-minute screen-off delivery test", "10分間画面オフ配信テスト"),
        "10 分钟实时交付实验" to Translation("10-minute real-time delivery test", "10分間リアルタイム配信テスト"),
        "20 分钟快速续航测试" to Translation("20-minute quick battery test", "20分間クイックバッテリーテスト"),
        "60 分钟正式续航测试" to Translation("60-minute formal battery test", "60分間正式バッテリーテスト"),
        "后台交付与续航测试报告" to Translation("Background delivery and battery test report", "バックグラウンド配信・バッテリーテスト報告"),
        "1. 持续采样，并在息屏时近实时交付" to Translation("1. Continuous sampling with near-real-time screen-off delivery", "1. 継続測定し、画面オフ時もほぼリアルタイムで配信"),
        "2. 持续采样，但息屏数据被缓存或批量补发" to Translation("2. Continuous sampling, but screen-off data was buffered or delivered in batches", "2. 継続測定したが、画面オフ時のデータはバッファーまたは一括配信"),
        "3. 息屏后停止采样，或没有息屏样本" to Translation("3. Sampling stopped after screen-off, or no screen-off samples", "3. 画面オフ後に測定停止、または画面オフ時サンプルなし"),
        "4. 会话或服务被终止/重启" to Translation("4. Session or service was stopped/restarted", "4. セッションまたはサービスが停止／再起動"),
        "5. 数据不足，无法证明持续采样" to Translation("5. Insufficient data to prove continuous sampling", "5. 継続測定を証明するデータが不足"),
        "5. 数据不足：没有息屏窗口" to Translation("5. Insufficient data: no screen-off window", "5. データ不足：画面オフ区間なし"),
        "20 分钟续航测试只适合快速估算，正式结果至少测试 60 分钟" to Translation("A 20-minute battery test is only a quick estimate; formal results require at least 60 minutes", "20分間のテストは概算です。正式な結果には60分以上必要です"),
        "60 分钟正式结果只对正常佩戴场景有效" to Translation("Formal 60-minute results are valid only for the on-wrist scenario", "正式な60分結果は装着中シナリオのみ有効です"),
        "60 分钟正式续航测试只允许正常佩戴场景" to Translation("The formal 60-minute battery test requires on-wrist use", "正式な60分バッテリーテストは装着中のみ実行できます"),
        "Health Services 不可用" to Translation("Health Services unavailable", "Health Servicesを利用できません"),
        "发生未分类错误，请查看诊断日志" to Translation("An unclassified error occurred; check the diagnostic log", "分類されていないエラーが発生しました。診断ログを確認してください"),
        "后台心率传输运行中" to Translation("Background heart rate relay is running", "バックグラウンド心拍数転送を実行中"),
        "后台心率服务尚未运行" to Translation("Background heart rate service is not running", "バックグラウンド心拍数サービスは未実行です"),
        "后台心率回调尚未注册" to Translation("Background heart rate callback is not registered", "バックグラウンド心拍数コールバックは未登録です"),
        "后台心率会话尚未进入运行状态" to Translation("Background heart rate session is not active", "バックグラウンド心拍数セッションはまだ実行状態ではありません"),
        "回调已注册，正在等待首个心率样本" to Translation("Callback registered; waiting for the first heart rate sample", "コールバック登録済み。最初の心拍数サンプルを待機中"),
        "处理传感器可用性回调失败" to Translation("Failed to handle sensor availability callback", "センサー可用性コールバックの処理に失敗"),
        "处理心率数据回调失败" to Translation("Failed to handle heart rate callback", "心拍数データコールバックの処理に失敗"),
        "实时交付实验未记录到 WakeLock 获取事件，结果无效" to Translation("No WakeLock acquisition was recorded for the real-time delivery test; result is invalid", "リアルタイム配信テストでWakeLock取得が記録されなかったため、結果は無効です"),
        "尚未启动" to Translation("Not started", "未開始"),
        "尚未授予心率读取权限" to Translation("Heart rate permission has not been granted", "心拍数読み取り権限が許可されていません"),
        "已有后台测试正在运行" to Translation("A background test is already running", "バックグラウンドテストは既に実行中です"),
        "开始测试前至少需要 5 个有效心率样本" to Translation("At least 5 valid heart rate samples are required before testing", "テスト開始前に有効な心拍数サンプルが5件以上必要です"),
        "当前设备环境不满足测量要求" to Translation("This device environment does not meet measurement requirements", "現在のデバイス環境は測定要件を満たしていません"),
        "心率传感器暂时不可用，请确认手表已佩戴" to Translation("Heart rate sensor temporarily unavailable; confirm the watch is worn", "心拍数センサーを一時的に利用できません。Watchを装着しているか確認してください"),
        "心率数据已超时" to Translation("Heart rate data timed out", "心拍数データがタイムアウトしました"),
        "心率权限已被永久拒绝，请在系统设置中授权" to Translation("Heart rate permission was permanently denied; grant it in system settings", "心拍数権限が永続的に拒否されました。システム設定で許可してください"),
        "未佩戴基线" to Translation("Off-wrist baseline", "未装着ベースライン"),
        "未佩戴基线要求手表明确识别为未佩戴" to Translation("The off-wrist baseline requires the watch to report off-body state", "未装着ベースラインではWatchが未装着状態を明確に認識する必要があります"),
        "未找到附近的手机伴侣应用" to Translation("No nearby phone companion app found", "近くのスマートフォン連携アプリが見つかりません"),
        "本轮使用有超时上限的 PARTIAL_WAKE_LOCK，仅用于验证实时交付及额外耗电" to Translation("This run uses a time-limited PARTIAL_WAKE_LOCK only to verify real-time delivery and its extra power use", "今回は時間制限付きPARTIAL_WAKE_LOCKを使用し、リアルタイム配信と追加消費電力のみを検証します"),
        "正在初始化诊断环境" to Translation("Initializing diagnostic environment", "診断環境を初期化中"),
        "正在接收实时心率" to Translation("Receiving live heart rate", "リアルタイム心拍数を受信中"),
        "正在检查能力并注册回调" to Translation("Checking capabilities and registering callback", "機能を確認しコールバックを登録中"),
        "正在注销测量回调" to Translation("Unregistering measurement callback", "測定コールバックを解除中"),
        "正常佩戴测试要求心率数据可用，请先正确佩戴手表" to Translation("On-wrist testing requires available heart rate data; wear the watch correctly first", "装着テストには心拍数データが必要です。先にWatchを正しく装着してください"),
        "测试提前结束，未达到要求时长" to Translation("Test ended before the required duration", "必要時間に達する前にテストが終了しました"),
        "测试期间应用进程发生重启" to Translation("The app process restarted during the test", "テスト中にアプリプロセスが再起動しました"),
        "测量已停止" to Translation("Measurement stopped", "測定を停止しました"),
        "环境就绪，可以开始测量" to Translation("Environment ready; measurement can start", "環境の準備が完了し、測定を開始できます"),
        "界面诊断日志已清除；持久化日志未受影响" to Translation("On-screen diagnostic log cleared; persistent log was not changed", "画面上の診断ログを消去しました。保存ログには影響ありません"),
        "目标时长结束至少 5 分钟且亮屏等待 15 秒后，仍未收到覆盖窗口末端的样本" to Translation("No sample covering the end of the target window arrived after the 5-minute tail and 15-second screen-on wait", "目標時間後5分と画面点灯15秒の待機後も、対象区間末端を覆うサンプルを受信できませんでした"),
        "目标测试窗口的开头或结尾缺少样本，不能据此证明完整时段持续采样" to Translation("Samples are missing at the start or end of the target window; continuous sampling for the full period is not proven", "対象区間の開始または終了サンプルが不足し、全期間の継続測定を証明できません"),
        "系统电量没有下降 1%，不能据此认定零耗电" to Translation("No visible 1% battery drop does not mean zero power use", "表示上1%減っていなくても消費電力がゼロとは判断できません"),
        "续航测试要求先断开充电器" to Translation("Disconnect the charger before battery testing", "バッテリーテスト前に充電器を外してください"),
        "设备未报告 FEATURE_WATCH" to Translation("Device did not report FEATURE_WATCH", "デバイスがFEATURE_WATCHを報告していません"),
        "设备未报告支持 HEART_RATE_BPM" to Translation("Device did not report HEART_RATE_BPM support", "デバイスがHEART_RATE_BPM対応を報告していません"),
        "软件只能依据样本和佩戴状态判断，不能直接证明 PPG 灯是否点亮" to Translation("The app can only infer from samples and wear state; it cannot prove whether the PPG LEDs are on", "アプリはサンプルと装着状態から推定するだけで、PPG LEDの点灯を直接証明できません"),
    )

    private val phrases = mapOf(
        " 秒前" to Translation(" s ago", "秒前"),
        " 秒" to Translation(" s", "秒"),
        "技术日志（" to Translation("Technical log (", "技術ログ（"),
        "正在发送 " to Translation("Sending ", "送信中 "),
        "已发送 " to Translation("sent ", "送信済み "),
        " 条" to Translation("", "件"),
        "已连接 " to Translation("Connected to ", "接続済み "),
        "亮屏后请保持应用开启至少15秒，请勿停止测量" to Translation(
            "Keep the app open for at least 15 seconds after turning the screen on; do not stop measurement",
            "画面点灯後15秒以上アプリを開いたままにし、測定を停止しないでください",
        ),
        "20 分钟结果只适合快速估算，正式续航至少测试 60 分钟" to Translation(
            "The 20-minute result is only an estimate; formal battery testing requires at least 60 minutes",
            "20分の結果は概算です。正式なバッテリーテストは60分以上行ってください",
        ),
        "系统电量显示没有下降 1%，不能据此认定零耗电" to Translation(
            "No visible 1% battery drop does not mean zero power use",
            "表示上1%減っていなくても、消費電力がゼロとは判断できません",
        ),
        "未佩戴基线不能与正常佩戴结果混合比较" to Translation(
            "Do not compare the off-wrist baseline with on-wrist results",
            "未装着の基準値と装着時の結果を混在して比較しないでください",
        ),
        "软件只能依据样本和佩戴状态判断，不能直接证明传感器灯是否点亮" to Translation(
            "The app can only infer from samples and wear state; it cannot prove whether the sensor LEDs are on",
            "アプリはサンプルと装着状態から推定するだけで、センサーLEDの点灯を直接証明できません",
        ),
        "测试开始时未确认已断开充电" to Translation(
            "Charging was not confirmed disconnected when the test started",
            "テスト開始時に充電が外れていることを確認できませんでした",
        ),
        "切换发送频率失败: " to Translation("Failed to change relay rate: ", "送信頻度の変更に失敗："),
        "同步手机发送频率失败: " to Translation("Failed to sync phone relay rate: ", "スマートフォン送信頻度の同期に失敗："),
        "后台心率测量发生错误：" to Translation("Background heart rate error: ", "バックグラウンド心拍数エラー："),
        "手机 ACK 解析失败: " to Translation("Phone ACK parse failed: ", "スマートフォンACK解析失敗："),
        "手机控制指令解析失败: " to Translation("Phone control message parse failed: ", "スマートフォン制御メッセージ解析失敗："),
        "查找手机同步节点失败: " to Translation("Failed to find phone sync node: ", "スマートフォン同期ノード検索失敗："),
        "A. 是否持续采样: " to Translation("A. Continuous sampling: ", "A. 継続測定："),
        "B. 息屏时是否近实时交付: " to Translation("B. Near-real-time screen-off delivery: ", "B. 画面オフ時のほぼリアルタイム配信："),
        "判定: " to Translation("Classification: ", "判定："),
        "实际时长: " to Translation("Actual duration: ", "実時間："),
        "尾部缓存等待: " to Translation("Tail-buffer wait: ", "末尾バッファー待機："),
        "开始时间: " to Translation("Start time: ", "開始時刻："),
        "结束时间: " to Translation("End time: ", "終了時刻："),
        "报告生成时间: " to Translation("Report generated: ", "報告生成時刻："),
        "警告:" to Translation("Warnings:", "警告："),
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
