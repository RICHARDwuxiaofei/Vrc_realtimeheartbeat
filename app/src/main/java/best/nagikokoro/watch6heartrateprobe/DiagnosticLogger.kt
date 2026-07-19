package best.nagikokoro.watch6heartrateprobe

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class DiagnosticLogger private constructor(context: Context) {
    private val logDirectory = File(context.filesDir, "logs")
    private val activeLogFile = File(logDirectory, LOG_FILE_NAME)
    private val fileExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hr-probe-file-logger").apply { isDaemon = true }
    }
    private val _visibleEntries = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
    val visibleEntries: StateFlow<List<DiagnosticEntry>> = _visibleEntries.asStateFlow()

    @Volatile
    private var currentState: ProbeStatus = ProbeStatus.INITIALIZING

    fun updateState(state: ProbeStatus) {
        currentState = state
    }

    fun debug(eventCode: String, message: String, parameters: Map<String, Any?> = emptyMap()) =
        log(LogLevel.DEBUG, eventCode, message, parameters)

    fun info(eventCode: String, message: String, parameters: Map<String, Any?> = emptyMap()) =
        log(LogLevel.INFO, eventCode, message, parameters)

    fun warn(eventCode: String, message: String, parameters: Map<String, Any?> = emptyMap()) =
        log(LogLevel.WARN, eventCode, message, parameters)

    fun error(
        eventCode: String,
        message: String,
        throwable: Throwable,
        parameters: Map<String, Any?> = emptyMap(),
    ) = log(LogLevel.ERROR, eventCode, message, parameters, throwable)

    fun clearVisible() {
        info("UI_LOG_CLEARED", "界面诊断日志已清除；持久化日志未受影响")
        _visibleEntries.value = emptyList()
    }

    fun shutdown() {
        fileExecutor.shutdown()
    }

    private fun log(
        level: LogLevel,
        eventCode: String,
        message: String,
        parameters: Map<String, Any?>,
        throwable: Throwable? = null,
    ) {
        val entry = DiagnosticEntry(
            timestampMillis = System.currentTimeMillis(),
            level = level,
            eventCode = eventCode,
            appState = currentState,
            message = message,
            parameters = parameters,
        )
        _visibleEntries.value = (_visibleEntries.value + entry).takeLast(MAX_MEMORY_ENTRIES)

        val structuredLine = formatPersistentEntry(entry, throwable)
        when (level) {
            LogLevel.DEBUG -> Log.d(TAG, structuredLine)
            LogLevel.INFO -> Log.i(TAG, structuredLine)
            LogLevel.WARN -> Log.w(TAG, structuredLine)
            LogLevel.ERROR -> Log.e(TAG, structuredLine, throwable)
        }

        try {
            fileExecutor.execute { appendSafely(structuredLine) }
        } catch (throwableDuringQueue: Throwable) {
            Log.e(TAG, "event=FILE_LOG_QUEUE_FAILED state=${currentState.name}", throwableDuringQueue)
        }
    }

    private fun appendSafely(line: String) {
        try {
            if (!logDirectory.exists() && !logDirectory.mkdirs()) {
                throw IllegalStateException("Cannot create private log directory: ${logDirectory.absolutePath}")
            }
            rotateIfNeeded(line.toByteArray(Charsets.UTF_8).size.toLong() + 1L)
            FileWriter(activeLogFile, true).use { writer ->
                writer.append(line).append('\n')
                writer.flush()
            }
        } catch (writeFailure: Throwable) {
            Log.e(TAG, "event=FILE_LOG_WRITE_FAILED state=${currentState.name}", writeFailure)
        }
    }

    private fun rotateIfNeeded(incomingBytes: Long) {
        if (!activeLogFile.exists() || activeLogFile.length() + incomingBytes <= MAX_FILE_BYTES) return

        val oldest = File(logDirectory, "$LOG_FILE_NAME.2")
        if (oldest.exists() && !oldest.delete()) {
            Log.w(TAG, "event=FILE_LOG_DELETE_FAILED path=${oldest.absolutePath}")
        }
        val middle = File(logDirectory, "$LOG_FILE_NAME.1")
        if (middle.exists() && !middle.renameTo(oldest)) {
            Log.w(TAG, "event=FILE_LOG_ROTATE_FAILED from=${middle.absolutePath} to=${oldest.absolutePath}")
        }
        if (!activeLogFile.renameTo(middle)) {
            Log.w(TAG, "event=FILE_LOG_ROTATE_FAILED from=${activeLogFile.absolutePath} to=${middle.absolutePath}")
        }
    }

    private fun formatPersistentEntry(entry: DiagnosticEntry, throwable: Throwable?): String {
        val stackTrace = throwable?.let {
            StringWriter().also { buffer -> it.printStackTrace(PrintWriter(buffer)) }.toString()
                .replace("\r", "\\r")
                .replace("\n", "\\n")
        } ?: "-"
        val params = if (entry.parameters.isEmpty()) {
            "-"
        } else {
            entry.parameters.entries.joinToString(",") { "${it.key}=${it.value}" }
        }
        return buildString {
            append("time=").append(ISO_FORMATTER.format(Instant.ofEpochMilli(entry.timestampMillis)))
            append(" level=").append(entry.level.name)
            append(" event=").append(entry.eventCode)
            append(" state=").append(entry.appState.name)
            append(" message=\"").append(entry.message.replace("\"", "'")).append('\"')
            append(" params=\"").append(params.replace("\"", "'")).append('\"')
            append(" exceptionType=").append(throwable?.javaClass?.name ?: "-")
            append(" exceptionMessage=\"").append(throwable?.message?.replace("\"", "'") ?: "-").append('\"')
            append(" stackTrace=\"").append(stackTrace.replace("\"", "'")).append('\"')
        }
    }

    companion object {
        const val TAG = "HR_PROBE"
        const val LOG_FILE_NAME = "hr_probe.log"
        private const val MAX_MEMORY_ENTRIES = 100
        private const val MAX_FILE_BYTES = 1024L * 1024L
        private val ISO_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneId.systemDefault())

        @Volatile
        private var instance: DiagnosticLogger? = null

        fun get(context: Context): DiagnosticLogger = instance ?: synchronized(this) {
            instance ?: DiagnosticLogger(context.applicationContext).also { instance = it }
        }
    }
}
