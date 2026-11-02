package com.yashwant.paramapadida.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
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
     * Launches Kyoto Player or general video player for a local file or stream URL
     */
    fun openInKyotoPlayer(
        context: Context,
        title: String,
        localFilePath: String?,
        streamUrl: String?
    ) {
        val uri: Uri = when {
            !localFilePath.isNullOrBlank() && File(localFilePath).exists() -> {
                val file = File(localFilePath)
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            }
            !streamUrl.isNullOrBlank() -> {
                Uri.parse(streamUrl)
            }
            else -> {
                Toast.makeText(context, "No stream or local file available", Toast.LENGTH_SHORT).show()
                return
            }
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("title", title)

            if (isKyotoPlayerInstalled(context)) {
                setPackage(KYOTO_PLAYER_PACKAGE)
            }
        }

        try {
            if (isKyotoPlayerInstalled(context)) {
                context.startActivity(intent)
            } else {
                val chooser = Intent.createChooser(intent, "Play with Kyoto Player / External Player").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Error launching player: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
