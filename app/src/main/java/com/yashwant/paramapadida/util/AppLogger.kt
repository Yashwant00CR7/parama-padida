package com.yashwant.paramapadida.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.core.content.FileProvider
import com.yashwant.paramapadida.data.model.Lesson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

data class LogEntry(
    val id: Long,
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwableStr: String? = null
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))

    fun toFormattedString(): String {
        val base = "[$formattedTime] [${level.name}] [$tag] $message"
        return if (throwableStr.isNullOrBlank()) base else "$base\n$throwableStr"
    }
}

object AppLogger {
    private const val MAX_LOGS = 500
    private val idCounter = AtomicLong(0)
    private val logQueue = ConcurrentLinkedDeque<LogEntry>()
    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    private var logFile: File? = null
    var lastError: String? = null
        private set

    fun init(context: Context) {
        try {
            logFile = File(context.filesDir, "app_logs.txt")
            if (logFile?.exists() == true && (logFile?.length() ?: 0) > 1024 * 1024 * 2) {
                // If log file exceeds 2MB, prune it
                logFile?.delete()
            }
        } catch (e: Exception) {
            Log.e("AppLogger", "Failed to initialize log file", e)
        }

        // Setup global uncaught crash handler
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            e("CRASH", "Uncaught exception on thread ${thread.name}: ${throwable.message}", throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }

        i("AppLogger", "Logger initialized. App started on ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message, null)
    fun w(tag: String, message: String, throwable: Throwable? = null) = log(LogLevel.WARN, tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        lastError = if (throwable != null) "$message (${throwable.localizedMessage})" else message
        log(LogLevel.ERROR, tag, message, throwable)
    }

    private fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        val stackTrace = throwable?.let {
            val sw = StringWriter()
            it.printStackTrace(PrintWriter(sw))
            sw.toString().trim()
        }

        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message, throwable)
            LogLevel.INFO -> Log.i(tag, message, throwable)
            LogLevel.WARN -> Log.w(tag, message, throwable)
            LogLevel.ERROR -> Log.e(tag, message, throwable)
        }

        val entry = LogEntry(
            id = idCounter.incrementAndGet(),
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            throwableStr = stackTrace
        )

        logQueue.addLast(entry)
        while (logQueue.size > MAX_LOGS) {
            logQueue.pollFirst()
        }
        _logsFlow.value = logQueue.toList()

        // Append to file asynchronously or safely
        try {
            logFile?.let { file ->
                file.appendText("${entry.toFormattedString()}\n")
            }
        } catch (_: Exception) {}
    }

    fun clear() {
        logQueue.clear()
        _logsFlow.value = emptyList()
        lastError = null
        try {
            logFile?.delete()
        } catch (_: Exception) {}
        i("AppLogger", "Logs cleared")
    }

    fun copyToClipboard(context: Context, text: String): Boolean {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("ParamaPadida_Logs", text)
            clipboard.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            e("AppLogger", "Failed to copy logs to clipboard: ${e.message}", e)
            false
        }
    }

    /**
     * Builds a comprehensive diagnostic report containing device info, storage info,
     * Kyoto Player package analysis, available video players, and the full event log.
     */
    fun buildDiagnosticReport(context: Context, lesson: Lesson? = null): String {
        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        sb.appendLine("==================================================")
        sb.appendLine("PARAMA PADIDA SYSTEM & KYOTO PLAYER DIAGNOSTICS")
        sb.appendLine("Generated At: $now")
        sb.appendLine("App Version: 1.0.0 (Code 1)")
        sb.appendLine("==================================================")
        sb.appendLine()

        // 1. Device Info
        sb.appendLine("--- DEVICE & OS ---")
        sb.appendLine("Manufacturer: ${Build.MANUFACTURER}")
        sb.appendLine("Brand: ${Build.BRAND}")
        sb.appendLine("Model: ${Build.MODEL}")
        sb.appendLine("Device: ${Build.DEVICE}")
        sb.appendLine("Android OS: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        sb.appendLine()

        // 2. Storage Info
        sb.appendLine("--- STORAGE STATUS ---")
        val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        sb.appendLine("App Movies Dir: ${moviesDir?.absolutePath}")
        sb.appendLine("Movies Dir Exists: ${moviesDir?.exists() == true}")
        sb.appendLine("Movies Dir Writable: ${moviesDir?.canWrite() == true}")
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            val freeMB = freeBytes / (1024 * 1024)
            sb.appendLine("Internal Free Space: $freeMB MB")
        } catch (_: Exception) {}
        sb.appendLine()

        // 3. Kyoto Player Deep Inspection
        sb.appendLine("--- KYOTO PLAYER INSPECTION ---")
        val pm = context.packageManager
        val targetPackage = "com.kyotoplayer"
        try {
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(targetPackage, PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(targetPackage, PackageManager.GET_ACTIVITIES)
            }
            sb.appendLine("Package '$targetPackage': FOUND")
            sb.appendLine("Version Name: ${pkgInfo.versionName}")
            sb.appendLine("Version Code: ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkgInfo.longVersionCode else @Suppress("DEPRECATION") pkgInfo.versionCode}")
            sb.appendLine("Target SDK: ${pkgInfo.applicationInfo?.targetSdkVersion}")

            val activities = pkgInfo.activities
            if (activities.isNullOrEmpty()) {
                sb.appendLine("Activities declared: NONE found via PackageManager")
            } else {
                sb.appendLine("Activities declared (${activities.size}):")
                activities.forEach { act ->
                    sb.appendLine("  • ${act.name} [Exported: ${act.exported}]")
                }
            }

            // Launch Intent Test
            val launchIntent = pm.getLaunchIntentForPackage(targetPackage)
            sb.appendLine("Launch Intent for package: ${if (launchIntent != null) "FOUND (${launchIntent.component?.className})" else "NOT FOUND"}")

        } catch (e: PackageManager.NameNotFoundException) {
            sb.appendLine("Package '$targetPackage': NOT FOUND via getPackageInfo")
            sb.appendLine("Checking alternative packages matching '*kyoto*'...")
            try {
                val installedPackages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledPackages(0)
                }
                val matching = installedPackages.filter { it.packageName.contains("kyoto", ignoreCase = true) }
                if (matching.isNotEmpty()) {
                    sb.appendLine("Found similar packages:")
                    matching.forEach { sb.appendLine("  • ${it.packageName} (v${it.versionName})") }
                } else {
                    sb.appendLine("No packages containing 'kyoto' found in visible package list.")
                }
            } catch (ex: Exception) {
                sb.appendLine("Could not enumerate packages: ${ex.message}")
            }
        } catch (e: Exception) {
            sb.appendLine("Error inspecting '$targetPackage': ${e.message}")
        }
        sb.appendLine()

        // 4. Intent Resolution Tests
        sb.appendLine("--- INTENT RESOLUTION PROBES ---")
        val testUri = try {
            lesson?.localFilePath?.let { path ->
                val f = File(path)
                if (f.exists()) {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
                } else null
            } ?: Uri.parse("content://${context.packageName}.fileprovider/test/sample.mp4")
        } catch (_: Exception) {
            Uri.parse("content://${context.packageName}.fileprovider/test/sample.mp4")
        }
        sb.appendLine("Probe URI: $testUri")

        // Probe A: ACTION_VIEW + video/* targeted to com.kyotoplayer
        val probeA = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(testUri, "video/*")
            setPackage(targetPackage)
        }
        val resolveA = probeA.resolveActivity(pm)
        sb.appendLine("Probe A [ACTION_VIEW + video/* -> com.kyotoplayer]: ${resolveA?.className ?: "FAILED (No Activity found)"}")

        // Probe B: ACTION_VIEW + video/mp4 targeted to com.kyotoplayer
        val probeB = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(testUri, "video/mp4")
            setPackage(targetPackage)
        }
        val resolveB = probeB.resolveActivity(pm)
        sb.appendLine("Probe B [ACTION_VIEW + video/mp4 -> com.kyotoplayer]: ${resolveB?.className ?: "FAILED (No Activity found)"}")

        // Probe C: ACTION_VIEW + uri only (no mime) targeted to com.kyotoplayer
        val probeC = Intent(Intent.ACTION_VIEW).apply {
            data = testUri
            setPackage(targetPackage)
        }
        val resolveC = probeC.resolveActivity(pm)
        sb.appendLine("Probe C [ACTION_VIEW + URI only -> com.kyotoplayer]: ${resolveC?.className ?: "FAILED (No Activity found)"}")

        // Probe D: System-wide video players (VLC, MX Player, etc.)
        val systemVideoIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(testUri, "video/*")
        }
        val systemHandlers = pm.queryIntentActivities(systemVideoIntent, 0)
        sb.appendLine("System Video Handlers Available (${systemHandlers.size}):")
        if (systemHandlers.isEmpty()) {
            sb.appendLine("  (None found)")
        } else {
            systemHandlers.forEach { res ->
                val label = res.loadLabel(pm)
                sb.appendLine("  • $label (${res.activityInfo.packageName} / ${res.activityInfo.name})")
            }
        }
        sb.appendLine()

        // 5. Selected Lesson Info
        if (lesson != null) {
            sb.appendLine("--- SELECTED LESSON / FILE INFO ---")
            sb.appendLine("Episode #${lesson.videoIndex}: ${lesson.title}")
            sb.appendLine("Status: isDownloaded=${lesson.isDownloaded}, isWatched=${lesson.isWatched}")
            sb.appendLine("Local Path: ${lesson.localFilePath}")
            val file = lesson.localFilePath?.let { File(it) }
            if (file != null) {
                sb.appendLine("File Exists: ${file.exists()}")
                if (file.exists()) {
                    val sizeMB = String.format(Locale.US, "%.2f", file.length().toDouble() / (1024.0 * 1024.0))
                    sb.appendLine("File Size: $sizeMB MB (${file.length()} bytes)")
                    sb.appendLine("File Readable: ${file.canRead()}")
                    sb.appendLine("File Extension: ${file.extension}")
                    try {
                        val fileUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        sb.appendLine("FileProvider URI: $fileUri")
                    } catch (e: Exception) {
                        sb.appendLine("FileProvider URI Generation Failed: ${e.message}")
                    }
                }
            }
            sb.appendLine()
        }

        // 6. Recent Logs
        sb.appendLine("--- EVENT LOGS (${logQueue.size} ENTRIES) ---")
        logQueue.forEach { entry ->
            sb.appendLine(entry.toFormattedString())
        }
        sb.appendLine("==================== END OF REPORT ====================")

        return sb.toString()
    }
}
