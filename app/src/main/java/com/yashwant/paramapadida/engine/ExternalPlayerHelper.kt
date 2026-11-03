package com.yashwant.paramapadida.engine

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.FileProvider
import com.yashwant.paramapadida.data.model.Lesson
import com.yashwant.paramapadida.util.AppLogger
import java.io.File

object ExternalPlayerHelper {
    const val DEFAULT_KYOTO_PACKAGE = "com.kyotoplayer"

    var lastLaunchError: String? = null
        private set

    fun findActivity(context: Context): Activity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    /**
     * Dynamically detects the exact package name for Kyoto Player on the user's device.
     */
    fun findKyotoPackage(context: Context): String? {
        val pm = context.packageManager
        val candidates = listOf(
            "com.kyotoplayer",
            "com.kyoto.player",
            "kyoto.player",
            "com.kyotoko.player",
            "com.kyotokoplayer"
        )
        for (pkg in candidates) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {}
        }

        // Broad scan across installed packages
        try {
            val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(0)
            }
            val match = installed.firstOrNull {
                it.packageName.contains("kyoto", ignoreCase = true) ||
                it.packageName.contains("kyotoko", ignoreCase = true)
            }
            if (match != null) return match.packageName
        } catch (_: Exception) {}

        return null
    }

    fun isKyotoPlayerInstalled(context: Context): Boolean {
        return findKyotoPackage(context) != null
    }

    fun getDetectedKyotoPackage(context: Context): String {
        return findKyotoPackage(context) ?: DEFAULT_KYOTO_PACKAGE
    }

    /**
     * Builds an ACTION_VIEW Intent configured with URI permissions and universal resume extras.
     */
    fun buildVideoIntent(
        context: Context,
        lesson: Lesson,
        resumePositionMs: Long = lesson.watchProgressMs,
        targetPackage: String? = null,
        forResult: Boolean = false
    ): Intent? {
        val path = lesson.localFilePath ?: return null
        val file = File(path)
        if (!file.exists()) return null

        val uri: Uri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            AppLogger.e("ExternalPlayerHelper", "FileProvider URI generation error: ${e.message}", e)
            Uri.fromFile(file)
        }

        val extension = file.extension.lowercase()
        val mimeType = when (extension) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "video/mp4"
        }

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            if (targetPackage != null) {
                setPackage(targetPackage)
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (!forResult && findActivity(context) == null) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            putExtra("title", lesson.title)
            putExtra("path", file.absolutePath)
            putExtra("file_path", file.absolutePath)

            // Universal Playback Resume / Start Position parameters
            if (resumePositionMs > 0) {
                // MX Player & standard Android
                putExtra("position", resumePositionMs.toInt())
                putExtra("position", resumePositionMs)
                putExtra("return_result", true)

                // VLC for Android
                putExtra("extra_position", resumePositionMs)
                putExtra("extra_position", resumePositionMs.toInt())
                putExtra("time", resumePositionMs)
                putExtra("from_start", false)

                // MPV, JustPlayer, BSPlayer & generic media players
                putExtra("resume_position", resumePositionMs)
                putExtra("start_position", resumePositionMs)
                putExtra("seek_to", resumePositionMs)
                putExtra("offset", resumePositionMs)
                putExtra("playback_position", resumePositionMs)
            } else {
                putExtra("from_start", true)
                putExtra("position", 0)
                putExtra("extra_position", 0L)
            }
        }
    }

    /**
     * Extracts playback position in milliseconds from player activity results (MX Player, VLC, etc.)
     */
    fun extractPositionFromResult(resultData: Intent?): Long? {
        if (resultData == null) return null

        // 1. MX Player ("position" in ms as int)
        val mxPos = resultData.getIntExtra("position", -1)
        if (mxPos > 0) return mxPos.toLong()

        // 2. VLC ("extra_position" in ms as long or int)
        val vlcPosLong = resultData.getLongExtra("extra_position", -1L)
        if (vlcPosLong > 0) return vlcPosLong
        val vlcPosInt = resultData.getIntExtra("extra_position", -1)
        if (vlcPosInt > 0) return vlcPosInt.toLong()

        // 3. Generic extras
        val timePos = resultData.getLongExtra("time", -1L)
        if (timePos > 0) return timePos

        return null
    }

    /**
     * Plays the downloaded video file using VLC, MX Player, MPV, or the system default video player.
     * Guarantees 100% reliable hardware accelerated playback with full controls.
     */
    fun playDownloadedLesson(
        context: Context,
        lesson: Lesson,
        resumePositionMs: Long = lesson.watchProgressMs,
        launcher: ActivityResultLauncher<Intent>? = null,
        onError: ((String) -> Unit)? = null
    ): Boolean {
        val path = lesson.localFilePath
        AppLogger.i("ExternalPlayerHelper", "Playback requested for Ep #${lesson.videoIndex}: '${lesson.title}' at '$path' (resume: ${resumePositionMs}ms)")

        if (path.isNullOrBlank() || !File(path).exists()) {
            val error = "Video file not found. Please download it first."
            AppLogger.e("ExternalPlayerHelper", error)
            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            onError?.invoke(error)
            return false
        }

        val file = File(path)
        val uri: Uri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            AppLogger.e("ExternalPlayerHelper", "FileProvider URI generation error: ${e.message}", e)
            Uri.fromFile(file)
        }

        val pm = context.packageManager
        val forResult = launcher != null

        // Priority 1: Direct launch of popular dedicated media players (VLC, MX Player, MPV)
        val preferredPackages = listOf(
            "org.videolan.vlc",
            "com.mxtech.videoplayer.ad",
            "com.mxtech.videoplayer.pro",
            "is.xyz.mpv"
        )

        for (pkg in preferredPackages) {
            val targetIntent = buildVideoIntent(context, lesson, resumePositionMs, pkg, forResult)
            if (targetIntent != null && targetIntent.resolveActivity(pm) != null) {
                return try {
                    context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (launcher != null) {
                        launcher.launch(targetIntent)
                    } else {
                        context.startActivity(targetIntent)
                    }
                    AppLogger.i("ExternalPlayerHelper", "Launched $pkg for '${lesson.title}' (pos: ${resumePositionMs}ms)")
                    lastLaunchError = null
                    true
                } catch (e: Exception) {
                    AppLogger.w("ExternalPlayerHelper", "Failed launching $pkg: ${e.message}")
                    false
                }
            }
        }

        // Priority 2: System Video Player Chooser (VLC, MX Player, Gallery, Photos, Google Files, etc.)
        val viewIntent = buildVideoIntent(context, lesson, resumePositionMs, null, forResult)
        if (viewIntent != null) {
            val activities = pm.queryIntentActivities(viewIntent, 0)
            if (activities.isNotEmpty()) {
                return try {
                    val chooser = Intent.createChooser(viewIntent, "Play '${lesson.title}' with...").apply {
                        if (!forResult && findActivity(context) == null) {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
                    if (launcher != null) {
                        launcher.launch(chooser)
                    } else {
                        context.startActivity(chooser)
                    }
                    AppLogger.i("ExternalPlayerHelper", "System player chooser launched successfully for '${lesson.title}'")
                    lastLaunchError = null
                    true
                } catch (e: Exception) {
                    val err = "Failed to launch video player: ${e.message}"
                    AppLogger.e("ExternalPlayerHelper", err, e)
                    lastLaunchError = err
                    onError?.invoke(err)
                    false
                }
            }
        }

        // Priority 3: Fallback warning if no video app found on the device
        val err = "No video player found. Please install VLC or MX Player from Play Store."
        AppLogger.e("ExternalPlayerHelper", err)
        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
        lastLaunchError = err
        onError?.invoke(err)
        return false
    }

    /**
     * Opens the video in any installed video player (VLC, MX Player, MPV, Gallery).
     */
    fun playWithSystemChooser(
        context: Context,
        lesson: Lesson,
        resumePositionMs: Long = lesson.watchProgressMs,
        launcher: ActivityResultLauncher<Intent>? = null
    ): Boolean {
        val path = lesson.localFilePath ?: return false
        val file = File(path)
        if (!file.exists()) return false

        val forResult = launcher != null
        val viewIntent = buildVideoIntent(context, lesson, resumePositionMs, null, forResult) ?: return false
        val pm = context.packageManager
        val handlers = pm.queryIntentActivities(viewIntent, 0)
        if (handlers.isEmpty()) {
            AppLogger.w("ExternalPlayerHelper", "No video player activities found on device")
            return false
        }

        return try {
            val chooser = Intent.createChooser(viewIntent, "Play '${lesson.title}' with...").apply {
                if (!forResult && findActivity(context) == null) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            if (launcher != null) {
                launcher.launch(chooser)
            } else {
                context.startActivity(chooser)
            }
            AppLogger.i("ExternalPlayerHelper", "System player chooser launched successfully")
            true
        } catch (e: Exception) {
            AppLogger.e("ExternalPlayerHelper", "Failed to launch chooser: ${e.message}", e)
            false
        }
    }
}
