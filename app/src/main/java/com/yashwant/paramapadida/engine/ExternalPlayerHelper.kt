package com.yashwant.paramapadida.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.yashwant.paramapadida.data.model.Lesson
import java.io.File

object ExternalPlayerHelper {
    const val KYOTO_PLAYER_PACKAGE = "com.kyotoplayer"

    fun isKyotoPlayerInstalled(context: Context): Boolean {
        return try {
            val packageManager = context.packageManager
            packageManager.getPackageInfo(KYOTO_PLAYER_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Plays the downloaded video file in Kyoto Player
     */
    fun playDownloadedLesson(context: Context, lesson: Lesson) {
        val path = lesson.localFilePath
        if (path.isNullOrBlank() || !File(path).exists()) {
            Toast.makeText(context, "Video not downloaded yet. Please download it first.", Toast.LENGTH_SHORT).show()
            return
        }

        val file = File(path)
        val uri: Uri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            Uri.fromFile(file)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("title", lesson.title)

            if (isKyotoPlayerInstalled(context)) {
                setPackage(KYOTO_PLAYER_PACKAGE)
            }
        }

        try {
            if (isKyotoPlayerInstalled(context)) {
                context.startActivity(intent)
            } else {
                val chooser = Intent.createChooser(intent, "Play with Kyoto Player / Video Player").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Error opening player: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
