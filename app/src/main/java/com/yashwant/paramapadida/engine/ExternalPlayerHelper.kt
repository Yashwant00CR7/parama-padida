package com.yashwant.paramapadida.engine

import android.content.ComponentName
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
    const val KYOTO_PLAYER_PACKAGE = "com.kyotoplayer"

    var lastLaunchError: String? = null
        private set

    fun isKyotoPlayerInstalled(context: Context): Boolean {
        return try {
            val packageManager = context.packageManager
            packageManager.getPackageInfo(KYOTO_PLAYER_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Attempts to open the downloaded video file in Kyoto Player using multi-strategy
     * resolution. If Kyoto Player fails or is not found, gracefully falls back to the
     * system video player chooser and logs all details to AppLogger.
     */
    fun playDownloadedLesson(
        context: Context,
        lesson: Lesson,
        onError: ((String) -> Unit)? = null
    ): Boolean {
        val path = lesson.localFilePath
        AppLogger.i("ExternalPlayerHelper", "Attempting playback for episode #${lesson.videoIndex}: '${lesson.title}' at '$path'")

        if (path.isNullOrBlank() || !File(path).exists()) {
            val error = "Video file not found at: $path"
            AppLogger.e("ExternalPlayerHelper", error)
            Toast.makeText(context, "Video not downloaded yet. Please download it first.", Toast.LENGTH_SHORT).show()
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
            AppLogger.e("ExternalPlayerHelper", "FileProvider URI generation failed: ${e.message}", e)
            Uri.fromFile(file)
        }

        val extension = file.extension.lowercase()
        val mimeType = when (extension) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "video/mp4"
        }

        AppLogger.i("ExternalPlayerHelper", "Resolved URI: $uri, Extension: $extension, MIME: $mimeType, Size: ${file.length()} bytes")

        val isInstalled = isKyotoPlayerInstalled(context)
        AppLogger.i("ExternalPlayerHelper", "Kyoto Player installed check: $isInstalled")

        if (isInstalled) {
            // Attempt multiple strategies to launch Kyoto Player
            val launched = tryLaunchKyotoPlayer(context, uri, file, mimeType, lesson)
            if (launched) {
                lastLaunchError = null
                return true
            }
        }

        // Fallback: If Kyoto Player failed or not installed, open system chooser
        AppLogger.w("ExternalPlayerHelper", "Kyoto Player could not handle intent directly. Launching system video chooser fallback...")
        val fallbackSuccess = launchSystemChooser(context, uri, mimeType, lesson)
        if (!fallbackSuccess) {
            val failureMsg = lastLaunchError ?: "No video player available on device to play this file."
            AppLogger.e("ExternalPlayerHelper", failureMsg)
            Toast.makeText(context, failureMsg, Toast.LENGTH_LONG).show()
            onError?.invoke(failureMsg)
        }
        return fallbackSuccess
    }

    private fun tryLaunchKyotoPlayer(
        context: Context,
        uri: Uri,
        file: File,
        mimeType: String,
        lesson: Lesson
    ): Boolean {
        val pm = context.packageManager

        // Strategy 1: ACTION_VIEW with exact mimeType (e.g. video/mp4)
        val intentMime = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            setPackage(KYOTO_PLAYER_PACKAGE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("title", lesson.title)
        }
        if (intentMime.resolveActivity(pm) != null) {
            return tryStart(context, intentMime, "Strategy 1 (ACTION_VIEW + $mimeType)")
        } else {
            AppLogger.d("ExternalPlayerHelper", "Strategy 1 ($mimeType) not resolved by Kyoto Player")
        }

        // Strategy 2: ACTION_VIEW with wildcard video/*
        val intentWildcard = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            setPackage(KYOTO_PLAYER_PACKAGE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("title", lesson.title)
        }
        if (intentWildcard.resolveActivity(pm) != null) {
            return tryStart(context, intentWildcard, "Strategy 2 (ACTION_VIEW + video/*)")
        } else {
            AppLogger.d("ExternalPlayerHelper", "Strategy 2 (video/*) not resolved by Kyoto Player")
        }

        // Strategy 3: ACTION_VIEW with URI only (no MIME)
        val intentUriOnly = Intent(Intent.ACTION_VIEW).apply {
            data = uri
            setPackage(KYOTO_PLAYER_PACKAGE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("title", lesson.title)
        }
        if (intentUriOnly.resolveActivity(pm) != null) {
            return tryStart(context, intentUriOnly, "Strategy 3 (ACTION_VIEW + URI only)")
        } else {
            AppLogger.d("ExternalPlayerHelper", "Strategy 3 (URI only) not resolved by Kyoto Player")
        }

        // Strategy 4: Launch Intent for package with extras and URI
        val launchIntent = pm.getLaunchIntentForPackage(KYOTO_PLAYER_PACKAGE)
        if (launchIntent != null) {
            launchIntent.apply {
                action = Intent.ACTION_VIEW
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra("url", uri.toString())
                putExtra("video_url", uri.toString())
                putExtra("path", file.absolutePath)
                putExtra("title", lesson.title)
            }
            val started = tryStart(context, launchIntent, "Strategy 4 (Launch Intent + extras)")
            if (started) return true
        }

        // Strategy 5: Query declared activities in Kyoto Player and test exported ones
        try {
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(KYOTO_PLAYER_PACKAGE, PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(KYOTO_PLAYER_PACKAGE, PackageManager.GET_ACTIVITIES)
            }
            val activities = pkgInfo.activities
            if (!activities.isNullOrEmpty()) {
                val exported = activities.filter { it.exported }
                AppLogger.i("ExternalPlayerHelper", "Kyoto Player exported activities: ${exported.map { it.name }}")
                for (act in exported) {
                    val explicitIntent = Intent(Intent.ACTION_VIEW).apply {
                        component = ComponentName(KYOTO_PLAYER_PACKAGE, act.name)
                        setDataAndType(uri, mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("title", lesson.title)
                        putExtra("url", uri.toString())
                    }
                    if (tryStart(context, explicitIntent, "Strategy 5 (Explicit: ${act.name})")) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.w("ExternalPlayerHelper", "Strategy 5 activity check failed: ${e.message}")
        }

        val err = "Kyoto Player ($KYOTO_PLAYER_PACKAGE) does not declare an Activity to open local video files."
        lastLaunchError = err
        AppLogger.e("ExternalPlayerHelper", err)
        return false
    }

    private fun tryStart(context: Context, intent: Intent, strategyName: String): Boolean {
        return try {
            AppLogger.i("ExternalPlayerHelper", "Executing $strategyName...")
            context.startActivity(intent)
            AppLogger.i("ExternalPlayerHelper", "$strategyName succeeded!")
            true
        } catch (e: Exception) {
            val err = "$strategyName failed: ${e.javaClass.simpleName} - ${e.message}"
            AppLogger.e("ExternalPlayerHelper", err, e)
            lastLaunchError = err
            false
        }
    }

    private fun launchSystemChooser(
        context: Context,
        uri: Uri,
        mimeType: String,
        lesson: Lesson
    ): Boolean {
        return try {
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("title", lesson.title)
            }

            val chooser = Intent.createChooser(viewIntent, "Play '${lesson.title}' with...").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            AppLogger.i("ExternalPlayerHelper", "System chooser launched successfully")
            true
        } catch (e: Exception) {
            val err = "Failed to launch system chooser: ${e.message}"
            AppLogger.e("ExternalPlayerHelper", err, e)
            lastLaunchError = err
            false
        }
    }
}
