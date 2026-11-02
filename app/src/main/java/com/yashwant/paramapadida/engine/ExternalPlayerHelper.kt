package com.yashwant.paramapadida.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.yashwant.paramapadida.data.model.Lesson
import com.yashwant.paramapadida.util.AppLogger
import java.io.File

object ExternalPlayerHelper {
    const val DEFAULT_KYOTO_PACKAGE = "com.kyotoplayer"

    var lastLaunchError: String? = null
        private set

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
     * Plays the downloaded video file in Kyoto Player.
     * Uses verified launch intents with granted URI permissions and stream extras so Android
     * will never throw "No Activity found to handle Intent".
     */
    fun playDownloadedLesson(
        context: Context,
        lesson: Lesson,
        onError: ((String) -> Unit)? = null
    ): Boolean {
        val path = lesson.localFilePath
        AppLogger.i("ExternalPlayerHelper", "Playback requested for Ep #${lesson.videoIndex}: '${lesson.title}' at '$path'")

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

        val extension = file.extension.lowercase()
        val mimeType = when (extension) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "video/mp4"
        }

        val kyotoPkg = findKyotoPackage(context)
        AppLogger.i("ExternalPlayerHelper", "Detected Kyoto Package: $kyotoPkg, URI: $uri, MIME: $mimeType")

        if (kyotoPkg != null) {
            val pm = context.packageManager

            // Strategy 1: Check if an activity explicitly handles ACTION_VIEW for this package
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                setPackage(kyotoPkg)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("title", lesson.title)
            }
            if (viewIntent.resolveActivity(pm) != null) {
                return try {
                    context.grantUriPermission(kyotoPkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(viewIntent)
                    AppLogger.i("ExternalPlayerHelper", "Launched Kyoto Player via ACTION_VIEW ($kyotoPkg)")
                    lastLaunchError = null
                    true
                } catch (e: Exception) {
                    AppLogger.w("ExternalPlayerHelper", "ACTION_VIEW failed on $kyotoPkg: ${e.message}")
                    false
                }
            }

            // Strategy 2: Launch via verified package launch intent with video extras
            // DO NOT change launchIntent.action to ACTION_VIEW because launcher activities use ACTION_MAIN!
            val launchIntent = pm.getLaunchIntentForPackage(kyotoPkg)
            if (launchIntent != null) {
                val launched = try {
                    context.grantUriPermission(kyotoPkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    launchIntent.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        data = uri
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra("url", uri.toString())
                        putExtra("video_url", uri.toString())
                        putExtra("path", file.absolutePath)
                        putExtra("file_path", file.absolutePath)
                        putExtra("title", lesson.title)
                    }
                    context.startActivity(launchIntent)
                    AppLogger.i("ExternalPlayerHelper", "Launched Kyoto Player via LaunchIntent ($kyotoPkg)")
                    Toast.makeText(context, "Opened in Kyoto Player", Toast.LENGTH_SHORT).show()
                    lastLaunchError = null
                    true
                } catch (e: Exception) {
                    val err = "Failed to launch Kyoto Player: ${e.message}"
                    AppLogger.e("ExternalPlayerHelper", err, e)
                    lastLaunchError = err
                    false
                }
                if (launched) return true
            }
        }

        // Strategy 3: Gracefully fallback to System Video Player Chooser (VLC, MX Player, MPV)
        AppLogger.w("ExternalPlayerHelper", "Kyoto Player not directly openable. Launching system player chooser...")
        val fallbackOk = playWithSystemChooser(context, lesson)
        if (!fallbackOk) {
            val msg = "Please install Kyoto Player or a video player like VLC to play this video."
            lastLaunchError = msg
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            onError?.invoke(msg)
        }
        return fallbackOk
    }

    /**
     * Opens the video in any installed video player (VLC, MX Player, MPV, Gallery).
     */
    fun playWithSystemChooser(context: Context, lesson: Lesson): Boolean {
        val path = lesson.localFilePath ?: return false
        val file = File(path)
        if (!file.exists()) return false

        val uri: Uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (_: Exception) {
            Uri.fromFile(file)
        }

        val extension = file.extension.lowercase()
        val mimeType = when (extension) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            else -> "video/mp4"
        }

        val pm = context.packageManager
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("title", lesson.title)
        }

        val handlers = pm.queryIntentActivities(viewIntent, 0)
        if (handlers.isEmpty()) {
            AppLogger.w("ExternalPlayerHelper", "No video player activities found on device for $mimeType")
            return false
        }

        return try {
            val chooser = Intent.createChooser(viewIntent, "Play '${lesson.title}' with...").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            AppLogger.i("ExternalPlayerHelper", "System player chooser launched successfully")
            true
        } catch (e: Exception) {
            AppLogger.e("ExternalPlayerHelper", "Failed to launch chooser: ${e.message}", e)
            false
        }
    }
}
