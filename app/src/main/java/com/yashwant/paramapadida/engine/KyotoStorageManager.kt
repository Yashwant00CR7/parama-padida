package com.yashwant.paramapadida.engine

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.yashwant.paramapadida.data.local.AppDatabase
import com.yashwant.paramapadida.data.model.DownloadStatus
import com.yashwant.paramapadida.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object KyotoStorageManager {
    private const val TAG = "KyotoStorageManager"

    /**
     * Checks if the app has All Files Access on Android 11+ (API 30+)
     */
    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    /**
     * Opens system settings to request All Files Access on Android 11+
     */
    fun requestAllFilesAccess(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val genericIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(genericIntent)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Cannot launch All Files Access settings: ${e.message}")
                }
            }
        }
    }

    /**
     * Resolves the target download folder directly under Kyoto Player's locations.
     * Order of preference:
     * 1. Direct Kyoto Player App Data Directory: /storage/emulated/0/Android/data/<kyotoPkg>/files/Downloads/<title>
     * 2. Public Downloads / KyotoPlayer: /storage/emulated/0/Download/KyotoPlayer/<title>
     * 3. Public Movies / KyotoPlayer: /storage/emulated/0/Movies/KyotoPlayer/<title>
     * 4. Root /storage/emulated/0/KyotoPlayer/<title>
     * 5. App fallback storage
     */
    fun getKyotoCourseFolder(context: Context, courseTitle: String): File {
        val sanitizedTitle = courseTitle.replace(Regex("[^a-zA-Z0-9._ -]"), "_").trim()
        val kyotoPkg = ExternalPlayerHelper.findKyotoPackage(context) ?: ExternalPlayerHelper.DEFAULT_KYOTO_PACKAGE

        val candidates = mutableListOf<File>()
        val extStorage = Environment.getExternalStorageDirectory()

        // 1. Direct Kyoto Player package directory: Android/data/<kyotoPkg>/files/Downloads/
        val kyotoAppDataDir = File(extStorage, "Android/data/$kyotoPkg/files")
        candidates.add(File(kyotoAppDataDir, "Downloads/$sanitizedTitle"))
        candidates.add(File(kyotoAppDataDir, sanitizedTitle))
        candidates.add(File(kyotoAppDataDir, "Downloads"))
        candidates.add(kyotoAppDataDir)

        // 2. Public Download / KyotoPlayer directory: /storage/emulated/0/Download/KyotoPlayer/<title>
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        candidates.add(File(publicDownloads, "KyotoPlayer/$sanitizedTitle"))
        candidates.add(File(publicDownloads, "Kyotoko/$sanitizedTitle"))
        candidates.add(File(publicDownloads, "KyotoPlayer"))

        // 3. Public Movies / KyotoPlayer directory: /storage/emulated/0/Movies/KyotoPlayer/<title>
        val publicMovies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        candidates.add(File(publicMovies, "KyotoPlayer/$sanitizedTitle"))
        candidates.add(File(publicMovies, "Kyotoko/$sanitizedTitle"))
        candidates.add(File(publicMovies, "KyotoPlayer"))

        // 4. Root storage: /storage/emulated/0/KyotoPlayer/<title>
        candidates.add(File(extStorage, "KyotoPlayer/$sanitizedTitle"))
        candidates.add(File(extStorage, "KyotoPlayer"))

        for (candidate in candidates) {
            if (isWritable(candidate)) {
                AppLogger.i(TAG, "Selected Kyoto Player download destination: ${candidate.absolutePath}")
                return candidate
            }
        }

        // 5. Fallback: app's external movies folder under a designated KyotoPlayer directory
        val fallback = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir,
            "KyotoPlayer/$sanitizedTitle"
        )
        fallback.mkdirs()
        AppLogger.w(TAG, "Using fallback download folder: ${fallback.absolutePath}")
        return fallback
    }

    private fun isWritable(dir: File): Boolean {
        return try {
            if (!dir.exists()) {
                val created = dir.mkdirs()
                if (!created && !dir.exists()) return false
            }
            val testFile = File(dir, ".probe_${System.currentTimeMillis()}")
            if (testFile.createNewFile()) {
                testFile.delete()
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Registers downloaded file with Android MediaStore scanner and mirrors to Kyoto Player app directory if accessible.
     */
    fun registerAndSyncMedia(context: Context, file: File) {
        if (!file.exists() || file.length() == 0L) return

        // 1. Notify Android system media scanner so Kyoto Player and all media players see it immediately
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf("video/mp4", "video/x-matroska", "video/*")
        ) { path, uri ->
            AppLogger.i(TAG, "System MediaScanner indexed: $path -> $uri")
        }

        // 2. If the file is not already in Android/data/<kyotoPkg>/files, attempt mirror copy
        val kyotoPkg = ExternalPlayerHelper.findKyotoPackage(context) ?: ExternalPlayerHelper.DEFAULT_KYOTO_PACKAGE
        val kyotoAppDir = File(Environment.getExternalStorageDirectory(), "Android/data/$kyotoPkg/files")
        if (isWritable(kyotoAppDir) && !file.absolutePath.startsWith(kyotoAppDir.absolutePath)) {
            try {
                val targetDir = File(kyotoAppDir, "Downloads")
                targetDir.mkdirs()
                val targetFile = File(targetDir, file.name)
                if (!targetFile.exists() || targetFile.length() != file.length()) {
                    file.copyTo(targetFile, overwrite = true)
                    AppLogger.i(TAG, "Mirrored video to Kyoto app folder: ${targetFile.absolutePath}")
                    MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), arrayOf("video/mp4"), null)
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Kyoto mirror copy skipped: ${e.message}")
            }
        }
    }

    /**
     * Auto-migrates any legacy downloads from the previous private folder into Kyoto Player's storage.
     */
    suspend fun migrateLegacyDownloads(context: Context) = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getInstance(context)
            val lessonDao = db.lessonDao()
            val downloadedLessons = lessonDao.getAllDownloadedLessons()

            for (lesson in downloadedLessons) {
                val path = lesson.localFilePath ?: continue
                val oldFile = File(path)

                // If file is located inside Parama Padida private app storage, migrate it
                if (oldFile.exists() && oldFile.absolutePath.contains("com.yashwant.paramapadida")) {
                    val targetFolder = getKyotoCourseFolder(context, "Course_${lesson.courseId}")
                    val targetFile = File(targetFolder, oldFile.name)

                    try {
                        oldFile.copyTo(targetFile, overwrite = true)
                        if (targetFile.exists() && targetFile.length() > 0) {
                            oldFile.delete()
                            lessonDao.updateDownloadStatus(
                                lesson.id,
                                targetFile.absolutePath,
                                DownloadStatus.DOWNLOADED,
                                100
                            )
                            registerAndSyncMedia(context, targetFile)
                            AppLogger.i(TAG, "Migrated '${lesson.title}' -> ${targetFile.absolutePath}")
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Failed migrating '${lesson.title}': ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Legacy migration error: ${e.message}")
        }
    }
}
