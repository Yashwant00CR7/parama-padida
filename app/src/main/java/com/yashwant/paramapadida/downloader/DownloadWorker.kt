package com.yashwant.paramapadida.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yashwant.paramapadida.data.local.AppDatabase
import com.yashwant.paramapadida.data.model.DownloadStatus
import com.yashwant.paramapadida.data.model.Lesson
import com.yashwant.paramapadida.engine.YouTubeEngine
import com.yashwant.paramapadida.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Sequential batch downloader.
 * Ensures strictly ONE video downloads at a time to prevent high RAM/CPU spikes
 * and memory-killer terminations on low-to-mid memory Android devices.
 */
class DownloadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "DownloadWorker"
        const val UNIQUE_WORK_NAME = "parama_batch_downloader"
        const val CHANNEL_ID = "parama_download_channel"
        const val NOTIFICATION_ID = 1001

        const val KEY_QUALITY = "key_quality" // e.g. "720", "1080", "best"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val notification = createNotification(
            title = "Video Downloads",
            progress = 0,
            contentText = "Processing download queue..."
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val database = AppDatabase.getInstance(context)
        val lessonDao = database.lessonDao()
        val courseDao = database.courseDao()
        val quality = inputData.getString(KEY_QUALITY) ?: "720"

        createNotificationChannel()

        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            AppLogger.w(TAG, "setForeground warning: ${e.message}")
        }

        AppLogger.i(TAG, "Sequential download worker active")

        // Reset any leftover DOWNLOADING tasks from prior crashed runs back to QUEUED
        try {
            lessonDao.resetInterruptedDownloads()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Reset interrupted downloads: ${e.message}")
        }

        var completedInSession = 0

        try {
            while (!isStopped) {
                // Fetch the next queued item (ordered by courseId and videoIndex)
                val lesson = lessonDao.getNextQueuedLesson()
                if (lesson == null) {
                    AppLogger.i(TAG, "No more items in queue. Batch finished.")
                    break
                }

                val course = courseDao.getCourseByIdSync(lesson.courseId)
                val courseTitle = course?.title ?: "Course"
                val remainingQueue = lessonDao.countQueuedLessons()

                AppLogger.i(TAG, "Starting download for Ep #${lesson.videoIndex}: '${lesson.title}' ($remainingQueue queued)")

                // Mark current lesson as DOWNLOADING
                lessonDao.updateDownloadStatus(lesson.id, null, DownloadStatus.DOWNLOADING, 0)

                // Update notification for this episode
                notificationManager.notify(
                    NOTIFICATION_ID,
                    createNotification(
                        title = "Downloading: ${lesson.title}",
                        progress = 0,
                        contentText = "Ep #${lesson.videoIndex} • $remainingQueue in queue"
                    )
                )

                val success = downloadSingleLesson(lesson, courseTitle, quality, remainingQueue)
                if (success) {
                    completedInSession++
                }

                // Explicit garbage collection hint to release memory between video downloads
                System.gc()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Exception in sequential batch loop: ${e.message}", e)
        }

        if (isStopped) {
            AppLogger.w(TAG, "Sequential download stopped / cancelled")
            lessonDao.resetAllQueuedLessons()
            notificationManager.notify(
                NOTIFICATION_ID,
                createNotification(
                    title = "Downloads Paused",
                    progress = 0,
                    contentText = "Queue stopped"
                )
            )
            return@withContext Result.failure()
        }

        notificationManager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Downloads Complete")
                .setContentText("Finished downloading $completedInSession episode(s)")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .setOngoing(false)
                .build()
        )

        Result.success()
    }

    private suspend fun downloadSingleLesson(
        lesson: Lesson,
        courseTitle: String,
        quality: String,
        remainingInQueue: Int
    ): Boolean {
        val database = AppDatabase.getInstance(context)
        val lessonDao = database.lessonDao()

        val courseFolder = YouTubeEngine.getCourseFolder(context, courseTitle)
        val sanitizedTitle = lesson.title.replace(Regex("[^a-zA-Z0-9._ -]"), "_").trim()
        val indexPrefix = String.format("%02d - ", lesson.videoIndex)
        val outputTemplate = "${courseFolder.absolutePath}/$indexPrefix$sanitizedTitle.%(ext)s"

        // YouTube formats: b selects combined pre-muxed stream; 22 (720p), 18 (360p) are universal MP4 fallbacks
        val formatOption = when (quality) {
            "1080" -> "b[height<=1080]/best[height<=1080]/b/best/22/18/w"
            "720" -> "b[height<=720]/best[height<=720]/b/best/22/18/w"
            "480" -> "b[height<=480]/best[height<=480]/b/best/18/w"
            "audio" -> "140/ba[ext=m4a]/ba/bestaudio"
            else -> "b[height<=720]/best[height<=720]/b/best/22/18/w"
        }

        return try {
            val request = YoutubeDLRequest(lesson.webUrl).apply {
                addOption("-o", outputTemplate)
                addOption("-f", formatOption)
                addOption("--write-subs")
                addOption("--write-auto-subs")
                addOption("--sub-lang", "en.*,en")
                addOption("--convert-subs", "vtt")
                addOption("--no-mtime")
                addOption("--no-part")
                addOption("--no-warnings")
                addOption("--retries", "3")
                addOption("--fragment-retries", "3")
            }

            var lastReportedProgress = 0
            val bgScope = CoroutineScope(Dispatchers.IO)

            YoutubeDL.getInstance().execute(request) { progress, etaInSeconds, _ ->
                val intProgress = progress.toInt().coerceIn(0, 100)
                if (intProgress - lastReportedProgress >= 10 || (intProgress == 100 && lastReportedProgress != 100)) {
                    lastReportedProgress = intProgress
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        createNotification(
                            title = "Downloading: ${lesson.title}",
                            progress = intProgress,
                            contentText = "Ep #${lesson.videoIndex} ($intProgress% • $remainingInQueue in queue)"
                        )
                    )
                    // Asynchronously persist progress in Room without blocking the native Python thread
                    bgScope.launch {
                        try {
                            lessonDao.updateDownloadStatus(lesson.id, null, DownloadStatus.DOWNLOADING, intProgress)
                        } catch (_: Exception) {}
                    }
                }
            }

            // Find downloaded media file matching the index prefix in the folder (exclude temp and subtitle files)
            val downloadedFile = courseFolder.listFiles()?.firstOrNull { file ->
                file.name.startsWith(indexPrefix) &&
                    file.length() > 0 &&
                    !file.name.endsWith(".part") &&
                    !file.name.endsWith(".ytdl") &&
                    !file.name.endsWith(".vtt") &&
                    !file.name.endsWith(".srt")
            }

            if (downloadedFile != null && downloadedFile.exists()) {
                AppLogger.i(TAG, "Ep #${lesson.videoIndex} download succeeded: '${downloadedFile.name}' (${downloadedFile.length()} bytes)")
                lessonDao.updateDownloadStatus(
                    lesson.id,
                    downloadedFile.absolutePath,
                    DownloadStatus.DOWNLOADED,
                    100
                )
                // Register with system MediaScanner and mirror to Kyoto Player storage
                com.yashwant.paramapadida.engine.KyotoStorageManager.registerAndSyncMedia(context, downloadedFile)

                // Also scan any companion subtitle files for media scanners
                courseFolder.listFiles()?.filter {
                    it.name.startsWith(indexPrefix) && (it.extension.equals("vtt", ignoreCase = true) || it.extension.equals("srt", ignoreCase = true))
                }?.forEach { subFile ->
                    android.media.MediaScannerConnection.scanFile(context, arrayOf(subFile.absolutePath), null, null)
                }

                true
            } else {
                val err = "File not found after download for index ${lesson.videoIndex} in ${courseFolder.absolutePath}"
                AppLogger.e(TAG, err)
                lessonDao.updateDownloadStatus(lesson.id, null, DownloadStatus.FAILED, 0)
                false
            }
        } catch (e: Exception) {
            val errorMsg = e.localizedMessage ?: "Unknown error"
            AppLogger.e(TAG, "Ep #${lesson.videoIndex} download failed: $errorMsg", e)
            lessonDao.updateDownloadStatus(lesson.id, null, DownloadStatus.FAILED, 0)
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of downloading course videos"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, progress: Int, contentText: String) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
}
