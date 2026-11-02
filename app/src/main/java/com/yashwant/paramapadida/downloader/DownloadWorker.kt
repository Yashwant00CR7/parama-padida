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
import kotlinx.coroutines.Dispatchers
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

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val database = AppDatabase.getInstance(context)
        val lessonDao = database.lessonDao()
        val courseDao = database.courseDao()
        val quality = inputData.getString(KEY_QUALITY) ?: "720"

        createNotificationChannel()

        // Set initial foreground notification
        val initialNotification = createNotification(
            title = "Download Queue Active",
            progress = 0,
            contentText = "Starting sequential batch download..."
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setForeground(ForegroundInfo(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        } else {
            setForeground(ForegroundInfo(NOTIFICATION_ID, initialNotification))
        }

        AppLogger.i(TAG, "Sequential download worker started")

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

                AppLogger.i(TAG, "Processing ep #${lesson.videoIndex}: '${lesson.title}' ($remainingQueue remaining in queue)")

                // Mark current lesson as DOWNLOADING
                lessonDao.updateDownloadStatus(lesson.id, null, DownloadStatus.DOWNLOADING, 0)

                // Update notification for this episode
                notificationManager.notify(
                    NOTIFICATION_ID,
                    createNotification(
                        title = "Downloading: ${lesson.title}",
                        progress = 0,
                        contentText = "Ep #${lesson.videoIndex} • $remainingQueue left in queue"
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
            AppLogger.w(TAG, "Sequential download stopped / cancelled by user or system")
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

        val formatOption = when (quality) {
            "720" -> "b[height<=720]/best[height<=720]/b/best"
            "1080" -> "b[height<=1080]/best[height<=1080]/b/best"
            "audio" -> "ba/bestaudio"
            else -> "b[height<=720]/best[height<=720]/b/best"
        }

        return try {
            val request = YoutubeDLRequest(lesson.webUrl).apply {
                addOption("-o", outputTemplate)
                addOption("-f", formatOption)
                addOption("--no-mtime")
                addOption("--no-part")
                addOption("--no-warnings")
                addOption("--retries", "3")
                addOption("--fragment-retries", "3")
            }

            var lastProgress = 0
            YoutubeDL.getInstance().execute(request) { progress, etaInSeconds, _ ->
                val intProgress = progress.toInt().coerceIn(0, 100)
                if (intProgress != lastProgress) {
                    lastProgress = intProgress
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        createNotification(
                            title = "Downloading: ${lesson.title}",
                            progress = intProgress,
                            contentText = "Ep #${lesson.videoIndex} ($intProgress% • ${remainingInQueue} left)"
                        )
                    )
                    // Persist live progress to Room so the UI updates
                    kotlinx.coroutines.runBlocking {
                        lessonDao.updateDownloadStatus(lesson.id, null, DownloadStatus.DOWNLOADING, intProgress)
                    }
                }
            }

            // Find downloaded file matching the index prefix in the folder
            val downloadedFile = courseFolder.listFiles()?.firstOrNull { file ->
                file.name.startsWith(indexPrefix) && file.length() > 0 && !file.name.endsWith(".part") && !file.name.endsWith(".ytdl")
            }

            if (downloadedFile != null && downloadedFile.exists()) {
                AppLogger.i(TAG, "Ep #${lesson.videoIndex} download succeeded: '${downloadedFile.name}' (${downloadedFile.length()} bytes)")
                lessonDao.updateDownloadStatus(
                    lesson.id,
                    downloadedFile.absolutePath,
                    DownloadStatus.DOWNLOADED,
                    100
                )
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
